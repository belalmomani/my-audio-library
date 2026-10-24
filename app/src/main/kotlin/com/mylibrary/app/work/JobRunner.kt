package com.mylibrary.app.work

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.mylibrary.app.audio.AudioDecoder
import com.mylibrary.app.cloud.AuthRepository
import com.mylibrary.app.cloud.BackendApi
import com.mylibrary.app.cloud.CloudSync
import com.mylibrary.app.data.*
import com.mylibrary.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class StopException : Exception("stopped")

/**
 * ينفّذ مراحل المعالجة. كل مرحلة تحفظ نتيجتها في Room/الملفات، فتُستأنف من حيث توقفت ولا تُعاد من الصفر.
 * [isStopped] تأتي من WorkManager (إيقاف مؤقت/إلغاء/قتل العملية).
 */
class JobRunner(
    private val ctx: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val auth: AuthRepository,
    private val backend: BackendApi,
    private val sync: CloudSync,
) {
    private val jobs get() = db.jobs()

    private suspend fun stage(id: String, s: JobStage, p: Float, resumeAt: JobStage? = s) {
        jobs.setStage(id, s.name, p)
        if (resumeAt != null) jobs.setResume(id, resumeAt.name)
    }

    private fun check(isStopped: () -> Boolean) { if (isStopped()) throw StopException() }

    private suspend fun addWarning(id: String, w: String) {
        val j = jobs.get(id) ?: return
        if (!j.warnings.contains(w)) jobs.setWarnings(id, if (j.warnings.isEmpty()) w else j.warnings + "\n" + w)
    }

    suspend fun fail(id: String, t: Throwable) {
        jobs.addError(id, t.message ?: t.javaClass.simpleName)
        val j = jobs.get(id)
        jobs.setStage(id, JobStage.FAILED.name, j?.progress ?: 0f)
        jobs.setFinished(id, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ المرحلة 1: التحليل
    suspend fun process(jobId: String, isStopped: () -> Boolean) {
        var job = jobs.get(jobId) ?: return
        if (job.stage == JobStage.CANCELED.name) return
        val source = db.sources().get(job.sourceId) ?: error("المصدر غير موجود")

        // 1) فك الترميز (يُتخطى إن كان ملف العمل موجودًا)
        var work = source.workPath?.let(::File)?.takeIf { it.exists() }
        if (work == null) {
            stage(jobId, JobStage.EXTRACTING_AUDIO, 0f)
            val out = File(ctx.filesDir, "work/${source.id}.wav").also { it.parentFile?.mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var last = 0L
            try {
                val info = withContext(Dispatchers.IO) {
                    AudioDecoder.decodeToWav(ctx, Uri.parse(source.uri), out, isStopped) { p ->
                        val now = System.currentTimeMillis()
                        if (now - last > 800) { last = now; scope.launch { jobs.setProgress(jobId, p * 0.2f) } }
                    }
                }
                db.sources().setWork(source.id, out.absolutePath, info.durationMs)
            } catch (e: java.util.concurrent.CancellationException) {
                out.delete(); throw if (isStopped()) StopException() else e
            } finally { scope.cancel() }
            work = out
        }
        check(isStopped)
        val wav = WavFile.open(work)

        // 2) محرّك المعالجة
        job = jobs.get(jobId)!!
        if (job.cloud) runCloud(job, source, wav, isStopped) else runLocal(job, wav, isStopped)
    }

    // ----------------------------------------------------------------- محلي
    private suspend fun runLocal(job: JobEntity, wav: WavFile, isStopped: () -> Boolean) {
        val model = com.mylibrary.app.di.ServiceLocator.localSpeechModel
        if (model == null) {
            addWarning(job.id, "لا يوجد نموذج تعرّف على الكلام مثبّت على الجهاز، فلم تُستخرج كلمات تلقائيًا. " +
                "فُكّ ترميز الصوت وهو جاهز للقص اليدوي من شاشة مراجعة المقاطع، أو فعّل المعالجة السحابية.")
            jobs.setStage(job.id, JobStage.COMPLETED_WITH_WARNINGS.name, 1f)
            jobs.setFinished(job.id, System.currentTimeMillis())
            return
        }
        if (job.separate) addWarning(job.id, "فصل الموسيقى غير متاح محليًا؛ استُخدم الصوت الأصلي.")
        stage(job.id, JobStage.DIARIZING, 0.25f)
        val turns = if (job.diarize) com.mylibrary.app.di.ServiceLocator.localDiarizer?.diarize(wav).orEmpty() else emptyList()
        if (job.diarize && turns.isEmpty()) addWarning(job.id, "لم تُحدَّد هويات المتحدثين؛ عُوملت الكلمات كمتحدث واحد.")
        check(isStopped)

        stage(job.id, JobStage.TRANSCRIBING, 0.3f)
        val chunks = ChunkPlanner.plan(wav)
        val all = mutableListOf<TimedWord>()
        for ((i, c) in chunks.withIndex()) {
            check(isStopped)
            val part = model.transcribe(wav, c.startMs, c.endMs, job.language)
            // التراكب بين الأجزاء: تُسقَط الكلمة المكررة (نفس النص وتقارب التوقيت)
            for (w in part) if (all.none { it.text == w.text && kotlin.math.abs(it.startMs - w.startMs) < 250 }) all += w
            jobs.setProgress(job.id, 0.3f + 0.5f * (i + 1) / chunks.size)
        }
        val assigned = SpeakerAssigner.assign(all.sortedBy { it.startMs }, turns)
        persistTranscript(job, assigned.map { Triple(it.word, it.speaker ?: if (turns.isEmpty()) "LOCAL_0" else null, it.overlap) },
            turns.map { it.label }.distinct().ifEmpty { listOf("LOCAL_0") }.associateWith { null })
    }

    // ---------------------------------------------------------------- سحابي
    private suspend fun runCloud(job: JobEntity, source: SourceEntity, wav: WavFile, isStopped: () -> Boolean) {
        val s = settings.current
        require(s.allowCloudProcessing) { "المعالجة السحابية غير مسموحة في الإعدادات" }
        val session = auth.session.value ?: error("سجّل الدخول لاستخدام المعالجة السحابية")
        var serverId = job.serverJobId

        if (serverId == null) {
            stage(job.id, JobStage.UPLOADING, 0.2f)
            val key = "users/${session.userId}/tmp/${job.id}.${source.name.substringAfterLast('.', "bin").take(5)}"
            val size = if (source.sizeBytes > 0) source.sizeBytes else error("حجم الملف غير معروف")
            val uri = Uri.parse(source.uri)
            val type = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val p = backend.presign(key, "put", type, size)
            backend.put(p, Http_stream(size, type) { ctx.contentResolver.openInputStream(uri) ?: error("تعذّر فتح الملف") })
            check(isStopped)
            serverId = backend.createJob(key, job.separate, job.diarize, job.language, job.keepOriginal)
            jobs.setServer(job.id, serverId, key)
        } else if (job.resumeStage == JobStage.FAILED.name || jobs.get(job.id)?.lastError != null) {
            runCatching { backend.resumeJob(serverId) } // قد تكون المهمة ما زالت تعمل؛ لا بأس
        }

        var result: JSONObject? = null
        while (result == null) {
            check(isStopped)
            val sj = backend.getJob(serverId)
            val st = when (sj.stage) {
                "EXTRACTING_AUDIO" -> JobStage.EXTRACTING_AUDIO
                "SEPARATING" -> JobStage.SEPARATING
                "DIARIZING" -> JobStage.DIARIZING
                "TRANSCRIBING" -> JobStage.TRANSCRIBING
                else -> null
            }
            when (sj.stage) {
                "FAILED" -> error(sj.error ?: "فشلت المعالجة على الخادم")
                "CANCELED" -> throw StopException()
                "COMPLETED", "COMPLETED_WITH_WARNINGS" -> {
                    sj.warnings.forEach { addWarning(job.id, it) }
                    result = sj.result ?: error("نتيجة الخادم فارغة")
                }
                else -> {
                    if (st != null) stage(job.id, st, 0.3f + 0.5f * sj.progress, resumeAt = JobStage.UPLOADING)
                    delay(3000)
                }
            }
        }
        // الصوت المعالج (بعد فصل الموسيقى) إن وُجد
        val pk = result.optString("processed_key").takeIf { it.isNotBlank() && it != "null" }
        if (pk != null && job.separate) {
            val dst = File(ctx.filesDir, "work/${job.id}_vocals.wav")
            if (!dst.exists()) backend.download(backend.presign(pk, "get"), dst)
            jobs.setProcessed(job.id, dst.absolutePath)
        }
        val speakersJson = result.getJSONArray("speakers")
        val labels = LinkedHashMap<String, FloatArray?>()
        for (i in 0 until speakersJson.length()) {
            val o = speakersJson.getJSONObject(i)
            val emb = o.optJSONArray("embedding")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
            labels[o.getString("label")] = emb
        }
        val wordsJson = result.getJSONArray("words")
        val rows = ArrayList<Triple<TimedWord, String?, Double>>(wordsJson.length())
        for (i in 0 until wordsJson.length()) {
            val w = wordsJson.getJSONObject(i)
            rows += Triple(
                TimedWord(w.getString("text"), w.getLong("start_ms"), w.getLong("end_ms"), w.optDouble("confidence").takeIf { !it.isNaN() }),
                w.optString("speaker").takeIf { it.isNotBlank() && it != "null" },
                w.optDouble("overlap", 0.0),
            )
        }
        persistTranscript(job, rows, labels)
    }

    private fun Http_stream(size: Long, type: String, open: () -> java.io.InputStream) =
        com.mylibrary.app.cloud.Http.streamBody(type, size, open)

    /** يحفظ المتحدثين والكلمات المرشّحة ثم ينتظر مراجعة المستخدم قبل الاستخراج. */
    private suspend fun persistTranscript(job: JobEntity, rows: List<Triple<TimedWord, String?, Double>>, labels: Map<String, FloatArray?>) {
        val libs = db.libraries().all().mapNotNull { l ->
            l.embeddingJson?.let { j -> runCatching { org.json.JSONArray(j).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }.getOrNull()?.let { l.id to it } }
        }.toMap()
        val speakers = labels.entries.mapIndexed { i, (label, emb) ->
            SpeakerEntity(
                id = UUID.nameUUIDFromBytes("${job.id}:$label".toByteArray()).toString(), sourceId = job.sourceId, jobId = job.id,
                label = label, ordinal = i + 1,
                // اقتراح فقط (يمكن للمستخدم تغييره في شاشة المراجعة)؛ لا يعني معرفة هوية حقيقية
                libraryId = emb?.let { VoiceMatcher.bestMatch(it, libs) },
                embeddingJson = emb?.joinToString(prefix = "[", postfix = "]"),
            )
        }
        val idByLabel = speakers.associate { it.label to it.id }
        val cands = rows.mapIndexed { i, (w, spk, ov) ->
            CandidateEntity(
                id = UUID.nameUUIDFromBytes("${job.id}:$i".toByteArray()).toString(), jobId = job.id, sourceId = job.sourceId,
                speakerId = spk?.let { idByLabel[it] }, text = w.text, startMs = w.startMs, endMs = w.endMs,
                confidence = w.confidence, overlap = ov,
            )
        }.filter { it.endMs > it.startMs }
        db.withTransaction {
            db.speakers().upsertAll(speakers)
            db.candidates().insertAll(cands)
            jobs.setFound(job.id, speakers.size, cands.size)
        }
        if (cands.isEmpty()) {
            addWarning(job.id, "لم يُكتشف أي كلام في التسجيل.")
            jobs.setStage(job.id, JobStage.COMPLETED_WITH_WARNINGS.name, 1f)
            jobs.setFinished(job.id, System.currentTimeMillis())
            return
        }
        jobs.setStage(job.id, JobStage.AWAITING_SPEAKER_REVIEW.name, 0.8f)
        jobs.setResume(job.id, JobStage.AWAITING_SPEAKER_REVIEW.name)
        Notifications.reviewReady(ctx, job.id)
    }

    // ------------------------------------------------------ المرحلة 2: استخراج الكلمات
    suspend fun extract(jobId: String, isStopped: () -> Boolean) {
        val job = jobs.get(jobId) ?: return
        if (job.stage == JobStage.CANCELED.name) return
        val source = db.sources().get(job.sourceId) ?: error("المصدر غير موجود")
        stage(jobId, JobStage.EXTRACTING_WORDS, 0.8f)

        val audio = job.processedPath?.takeIf { job.useProcessedAudio }?.let(::File)?.takeIf { it.exists() }
            ?: source.workPath?.let(::File)?.takeIf { it.exists() }
            ?: error("ملف الصوت المؤقت غير موجود (حُذف). أعد استيراد التسجيل.")
        val wav = WavFile.open(audio)

        // كل متحدث يجب أن يرتبط بمكتبة: غير المرتبطين تُنشأ لهم مكتبات جديدة
        val speakers = db.speakers().forJob(jobId).map { sp ->
            if (sp.libraryId != null) sp else {
                val lib = library.createLibrary("المتحدث ${ordinalName(sp.ordinal)}")
                db.speakers().assign(sp.id, lib)
                sp.copy(libraryId = lib)
            }
        }
        val libOf = speakers.associate { it.id to it.libraryId!! }

        // (أ) تقييم جودة كل مرشّح على دفعات
        var scored = 0
        val total = db.candidates().count(jobId).coerceAtLeast(1)
        while (true) {
            check(isStopped)
            val batch = db.candidates().unscored(jobId, 200)
            if (batch.isEmpty()) break
            for (c in batch) {
                val q = runCatching { WordClipper.clip(wav, c.startMs, c.endMs).quality().score }.getOrDefault(0.0)
                db.candidates().setQuality(c.id, q)
            }
            scored += batch.size
            jobs.setProgress(jobId, 0.8f + 0.1f * scored / total)
        }

        // (ب) خطة الإضافة: منع التكرار داخل المكتبة نفسها
        val pending = db.candidates().pending(jobId)
        val assigned = pending.filter { it.speakerId != null && libOf.containsKey(it.speakerId) }
        val orphans = pending - assigned.toSet()
        val wcs = assigned.map {
            WordCandidate(it.id, libOf[it.speakerId]!!, it.text, it.startMs, it.endMs, it.confidence, it.quality ?: 0.0, it.overlap, it.sourceId)
        }
        val libs = libOf.values.toSet().toList()
        val existing = db.words().existing(libs).map { ExistingWord(it.id, it.libraryId, it.normalized, it.quality, it.userLocked) }
        val plan = WordIngestor.plan(wcs, existing)
        val byCand = assigned.associateBy { it.id }

        // (ج) كتابة الملفات (معرّفات حتمية: إعادة التشغيل بعد انقطاع تكتب فوق الملف نفسه)
        val newWords = ArrayList<WordEntity>()
        val now = System.currentTimeMillis()
        for ((i, add) in plan.toAdd.withIndex()) {
            check(isStopped)
            val c = add.candidate
            val wid = UUID.nameUUIDFromBytes("word:$jobId:${c.id}".toByteArray()).toString()
            val clip = WordClipper.clip(wav, c.startMs, c.endMs)
            val file = library.newWordFile(c.libraryId, wid)
            AtomicFileReplace.replace(file) { WavWriter.write(it, clip.samples, clip.sampleRate) }
            val shown = WordNormalizer.displayForm(c.text)
            newWords += WordEntity(
                id = wid, libraryId = c.libraryId, speakerId = byCand[c.id]?.speakerId, text = shown, normalized = add.normalized,
                fileName = FileNames.sanitize(shown) + ".wav", localPath = file.absolutePath, sourceId = source.id, sourceName = source.name,
                startMs = clip.startMs, endMs = clip.endMs, durationMs = clip.durationMs, sampleRate = clip.sampleRate,
                sizeBytes = file.length(), extractedAt = now, quality = clip.quality().score, sha256 = Hashing.sha256(file),
                processingVersion = "n${WordNormalizer.VERSION}-p1",
            )
            if (i % 50 == 0) jobs.setProgress(jobId, 0.9f + 0.05f * i / plan.toAdd.size.coerceAtLeast(1))
        }

        val reviews = plan.reviews.map {
            ReviewItemEntity(UUID.randomUUID().toString(), jobId, it.candidate.libraryId, source.id, it.candidate.text,
                it.candidate.startMs, it.candidate.endMs, it.reasons.joinToString(",") { r -> r.name }, it.candidate.asrConfidence, it.candidate.quality, null, now)
        } + plan.replaceSuggestions.map {
            ReviewItemEntity(UUID.randomUUID().toString(), jobId, it.candidate.libraryId, source.id, it.candidate.text,
                it.candidate.startMs, it.candidate.endMs, "SUGGEST_REPLACE", it.candidate.asrConfidence, it.candidate.quality, it.existingWordId, now)
        } + orphans.map {
            ReviewItemEntity(UUID.randomUUID().toString(), jobId, null, source.id, it.text, it.startMs, it.endMs, "NO_SPEAKER", it.confidence, it.quality, null, now)
        }

        // (د) معاملة واحدة: الكلمات + المراجعة + إغلاق المرشّحين + العدّادات
        var saved = 0
        db.withTransaction {
            val ids = db.words().insertAll(newWords)
            saved = ids.count { it != -1L }
            db.reviews().insertAll(reviews)
            db.candidates().markAllDone(jobId)
            jobs.addCounts(jobId, saved, plan.duplicatesSkipped, reviews.size, saved)
        }

        // (هـ) المزامنة السحابية إن فُعّلت
        var warn = jobs.get(jobId)!!.warnings.isNotEmpty() || reviews.isNotEmpty()
        if (sync.canSync) {
            stage(jobId, JobStage.UPLOADING, 0.95f, resumeAt = JobStage.UPLOADING)
            val sum = sync.syncPending { !isStopped() }
            if (sum.stoppedOffline) { addWarning(jobId, "تعذّر الرفع لانقطاع الاتصال؛ ستُرفع الكلمات المتبقية لاحقًا."); warn = true }
            if (sum.failed > 0) { addWarning(jobId, "فشل رفع ${sum.failed} كلمة؛ يمكنك إعادة المحاولة من الإعدادات."); warn = true }
        }
        jobs.setStage(jobId, (if (warn) JobStage.COMPLETED_WITH_WARNINGS else JobStage.COMPLETED).name, 1f)
        jobs.setFinished(jobId, System.currentTimeMillis())
        jobs.setResume(jobId, null)
    }

    private fun ordinalName(n: Int) = listOf("", "الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
        .getOrNull(n)?.takeIf { it.isNotEmpty() } ?: n.toString()
}
