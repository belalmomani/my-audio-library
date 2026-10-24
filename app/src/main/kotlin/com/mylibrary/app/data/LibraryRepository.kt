package com.mylibrary.app.data

import android.content.Context
import androidx.room.withTransaction
import com.mylibrary.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface AddResult {
    data class Added(val word: WordEntity) : AddResult
    data class Duplicate(val existing: WordEntity) : AddResult
    data class Invalid(val reason: String) : AddResult
}

sealed interface RenameResult {
    object Ok : RenameResult
    data class Conflict(val existing: WordEntity) : RenameResult
    object Invalid : RenameResult
}

class LibraryRepository(private val ctx: Context, val db: AppDatabase) {

    private fun libDir(libraryId: String) = File(ctx.filesDir, "libraries/$libraryId").also { it.mkdirs() }

    fun newWordFile(libraryId: String, wordId: String, version: Int = 0): File =
        File(libDir(libraryId), if (version == 0) "$wordId.wav" else "${wordId}_v$version.wav")

    fun summaries(): Flow<List<LibrarySummary>> = db.libraries().observeSummaries()
    fun wordsOf(libraryId: String): Flow<List<WordEntity>> = db.words().observeByLibrary(libraryId)

    private suspend fun audit(entity: String, id: String?, action: String, detail: String = "") =
        db.audit().add(AuditEntity(entity = entity, entityId = id, action = action, detail = detail, at = System.currentTimeMillis()))

    suspend fun createLibrary(name: String): String {
        val id = UUID.randomUUID().toString()
        db.libraries().upsert(LibraryEntity(id, name.trim().ifEmpty { "مكتبة جديدة" }, null, System.currentTimeMillis()))
        audit("library", id, "create")
        return id
    }

    suspend fun renameLibrary(id: String, name: String) {
        if (name.isBlank()) return
        db.libraries().rename(id, name.trim()); audit("library", id, "rename")
    }

    /** يحذف الكلمات وملفاتها محليًا. الحذف السحابي يتم عبر CloudRepository قبل استدعاء هذه الدالة. */
    suspend fun deleteLibrary(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.words().deleteByLibrary(id)
            db.libraries().delete(id)
            db.audit().add(AuditEntity(entity = "library", entityId = id, action = "delete", at = System.currentTimeMillis()))
        }
        File(ctx.filesDir, "libraries/$id").deleteRecursively()
    }

    /** يضيف كلمة بصوت المتحدث الأصلي؛ ترفض التكرار داخل المكتبة نفسها (القيد الأخير في الفهرس الفريد). */
    suspend fun addWord(
        libraryId: String,
        text: String,
        clip: ClipResult,
        source: SourceEntity?,
        speakerId: String? = null,
        wordId: String = UUID.randomUUID().toString(),
        reviewStatus: ReviewStatus = ReviewStatus.APPROVED,
        userLocked: Boolean = false,
    ): AddResult = withContext(Dispatchers.IO) {
        val shown = WordNormalizer.displayForm(text)
        val key = WordNormalizer.normalize(text)
        if (key.isEmpty()) return@withContext AddResult.Invalid("النص فارغ")
        if (clip.samples.isEmpty()) return@withContext AddResult.Invalid("المقطع فارغ")
        db.words().find(libraryId, key)?.let { return@withContext AddResult.Duplicate(it) }
        val file = newWordFile(libraryId, wordId)
        AtomicFileReplace.replace(file) { WavWriter.write(it, clip.samples, clip.sampleRate) }
        val w = WordEntity(
            id = wordId, libraryId = libraryId, speakerId = speakerId, text = shown, normalized = key,
            fileName = FileNames.sanitize(shown) + ".wav", localPath = file.absolutePath,
            sourceId = source?.id, sourceName = source?.name, startMs = clip.startMs, endMs = clip.endMs,
            durationMs = clip.durationMs, sampleRate = clip.sampleRate, sizeBytes = file.length(),
            extractedAt = System.currentTimeMillis(), reviewStatus = reviewStatus.name,
            quality = clip.quality().score, sha256 = Hashing.sha256(file),
            processingVersion = "n${WordNormalizer.VERSION}", userLocked = userLocked,
        )
        val inserted = db.words().insert(w)
        if (inserted == -1L) { file.delete(); return@withContext AddResult.Duplicate(db.words().find(libraryId, key)!!) }
        audit("word", wordId, "add", shown)
        AddResult.Added(w)
    }

    /**
     * استبدال مقطع كلمة: يُكتب الملف الجديد باسم جديد، ثم يُحدَّث السجل، وبعد نجاحه فقط يُحذف القديم.
     */
    suspend fun replaceClip(wordId: String, clip: ClipResult, source: SourceEntity?): Boolean = withContext(Dispatchers.IO) {
        val old = db.words().get(wordId) ?: return@withContext false
        if (clip.samples.isEmpty()) return@withContext false
        val version = (System.currentTimeMillis() / 1000).toInt()
        val file = newWordFile(old.libraryId, wordId, version)
        AtomicFileReplace.replace(file) { WavWriter.write(it, clip.samples, clip.sampleRate) }
        val updated = old.copy(
            localPath = file.absolutePath, sizeBytes = file.length(), durationMs = clip.durationMs,
            sampleRate = clip.sampleRate, startMs = clip.startMs, endMs = clip.endMs,
            sourceId = source?.id ?: old.sourceId, sourceName = source?.name ?: old.sourceName,
            quality = clip.quality().score, sha256 = Hashing.sha256(file), userLocked = true,
            uploadState = "PENDING", uploadAttempts = 0, lastError = null, extractedAt = System.currentTimeMillis(),
        )
        try {
            db.words().update(updated)
        } catch (t: Throwable) {
            file.delete(); throw t
        }
        if (old.localPath != updated.localPath) File(old.localPath).delete()
        audit("word", wordId, "replace")
        true
    }

    suspend fun deleteWord(id: String) = withContext(Dispatchers.IO) {
        val w = db.words().get(id) ?: return@withContext
        db.words().delete(id)
        File(w.localPath).delete()
        audit("word", id, "delete", w.text)
    }

    suspend fun renameWord(id: String, newText: String): RenameResult = withContext(Dispatchers.IO) {
        val w = db.words().get(id) ?: return@withContext RenameResult.Invalid
        val key = WordNormalizer.normalize(newText)
        if (key.isEmpty()) return@withContext RenameResult.Invalid
        if (key != w.normalized) db.words().find(w.libraryId, key)?.let { return@withContext RenameResult.Conflict(it) }
        val shown = WordNormalizer.displayForm(newText)
        db.words().update(w.copy(text = shown, normalized = key, fileName = FileNames.sanitize(shown) + ".wav", uploadState = "PENDING", uploadAttempts = 0))
        audit("word", id, "rename", shown)
        RenameResult.Ok
    }

    suspend fun mergePlan(targetId: String, sourceId: String): MergePlan {
        val t = db.words().byLibrary(targetId).map { WordRef(it.id, it.normalized, it.quality) }
        val s = db.words().byLibrary(sourceId).map { WordRef(it.id, it.normalized, it.quality) }
        return LibraryMergePlanner.plan(t, s)
    }

    /** دمج صريح بطلب المستخدم؛ تُحذف مكتبة المصدر بعده. */
    suspend fun mergeLibraries(targetId: String, sourceId: String, how: ConflictResolution) = withContext(Dispatchers.IO) {
        val tw = db.words().byLibrary(targetId)
        val sw = db.words().byLibrary(sourceId)
        val tr = tw.map { WordRef(it.id, it.normalized, it.quality) }
        val sr = sw.map { WordRef(it.id, it.normalized, it.quality) }
        val plan = LibraryMergePlanner.plan(tr, sr)
        val res = LibraryMergePlanner.resolve(plan, tr, sr, how)
        val byId = (tw + sw).associateBy { it.id }
        db.withTransaction {
            res.replaceTargetIds.forEach { db.words().delete(it) }
            res.deleteSourceIds.forEach { db.words().delete(it) }
            res.moveIds.forEach { id ->
                val w = byId[id]!!
                val newFile = newWordFile(targetId, w.id)
                File(w.localPath).copyTo(newFile, overwrite = true)
                db.words().delete(id)
                db.words().insert(w.copy(libraryId = targetId, localPath = newFile.absolutePath, uploadState = "PENDING", uploadAttempts = 0))
            }
            db.speakers().forLibrary(sourceId).forEach { db.speakers().assign(it.id, targetId) }
            db.reviews().reassign(sourceId, targetId)
            db.libraries().delete(sourceId)
            db.audit().add(AuditEntity(entity = "library", entityId = targetId, action = "merge", detail = sourceId, at = System.currentTimeMillis()))
        }
        // الملفات القديمة تُحذف بعد نجاح المعاملة فقط
        (res.replaceTargetIds + res.deleteSourceIds).forEach { byId[it]?.let { w -> File(w.localPath).delete() } }
        File(ctx.filesDir, "libraries/$sourceId").deleteRecursively()
    }

    suspend fun search(q: String, libraryId: String? = null): List<WordEntity> {
        val key = q.trim()
        if (key.isEmpty()) return emptyList()
        return if (libraryId == null) db.words().searchAll(key) else db.words().searchInLibrary(libraryId, key)
    }

    /** يحذف ملفات العمل المؤقتة (WAV المفكوك والنسخ المعالجة). لا يمس الكلمات المعتمدة أبدًا. */
    suspend fun cleanTemporaryFiles(): CleanResult = withContext(Dispatchers.IO) {
        var freed = 0L; var kept = 0
        for (s in db.sources().all()) {
            val path = s.workPath ?: continue
            if (db.reviews().countForSource(s.id) > 0) { kept++; continue } // ما زال لازمًا للمراجعة
            val f = File(path)
            freed += f.length(); f.delete()
            db.sources().clearWork(s.id)
        }
        File(ctx.cacheDir, "work").takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.forEach { freed += it.length(); it.delete() }
        for (j in db.jobs().finished()) {
            j.processedPath?.let { File(it).let { f -> freed += f.length(); f.delete() }; db.jobs().setProcessed(j.id, null) }
        }
        CleanResult(freed, kept)
    }
}

data class CleanResult(val freedBytes: Long, val keptForReview: Int)
