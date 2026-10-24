package com.mylibrary.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.sin

class CoreTest {
    private fun cand(lib: String, text: String, q: Double = 0.8, conf: Double? = 0.9, s: Long = 0, e: Long = 400, id: String = "$lib-$text-$s") =
        WordCandidate(id, lib, text, s, e, conf, q, 0.0, "src1")

    private fun tmp(): File = Files.createTempDirectory("ml").toFile()

    // ---- التطبيع
    @Test fun normalizesPunctuation() {
        assertEquals(WordNormalizer.normalize("أحمد"), WordNormalizer.normalize("أحمد،"))
        assertEquals(WordNormalizer.normalize("أحمد"), WordNormalizer.normalize("«أحمد!»"))
        assertEquals(WordNormalizer.normalize("أحمد"), WordNormalizer.normalize("أَحْمَد"))
    }

    @Test fun doesNotMergeDifferentWords() {
        assertNotEquals(WordNormalizer.normalize("ذهب"), WordNormalizer.normalize("يذهب"))
        assertNotEquals(WordNormalizer.normalize("الكتاب"), WordNormalizer.normalize("كتاب"))
    }

    // ---- الحالة 1
    @Test fun case1_repeatedWordSingleFile() {
        val c = listOf("أحمد", "تعال", "أحمد", "اذهب").mapIndexed { i, t -> cand("L1", t, s = i * 1000L, e = i * 1000L + 400) }
        val plan = WordIngestor.plan(c, emptyList())
        assertEquals(3, plan.toAdd.size)
        assertEquals(1, plan.duplicatesSkipped)
        assertEquals(1, plan.toAdd.count { it.normalized == WordNormalizer.normalize("أحمد") })
    }

    // ---- الحالة 2
    @Test fun case2_twoSpeakersEachGetOwnClip() {
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد"), cand("L2", "أحمد")), emptyList())
        assertEquals(2, plan.toAdd.size)
        assertEquals(setOf("L1", "L2"), plan.toAdd.map { it.candidate.libraryId }.toSet())
    }

    // ---- الحالة 3
    @Test fun case3_onlyNewWordsAdded() {
        val existing = listOf(ExistingWord("w1", "L1", WordNormalizer.normalize("أحمد"), 0.8))
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد،"), cand("L1", "افتح")), existing)
        assertEquals(listOf(WordNormalizer.normalize("افتح")), plan.toAdd.map { it.normalized })
        assertEquals(1, plan.duplicatesSkipped)
        assertTrue(plan.replaceSuggestions.isEmpty())
    }

    @Test fun betterClipIsOnlySuggestedNeverAuto() {
        val existing = listOf(ExistingWord("w1", "L1", WordNormalizer.normalize("أحمد"), 0.40))
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد", q = 0.9)), existing)
        assertTrue(plan.toAdd.isEmpty())
        assertEquals(1, plan.replaceSuggestions.size)
        val locked = WordIngestor.plan(listOf(cand("L1", "أحمد", q = 0.9)), existing.map { it.copy(userLocked = true) })
        assertTrue(locked.replaceSuggestions.isEmpty())
    }

    // ---- الحالة 5
    @Test fun case5_unclearWordGoesToReviewNotLibrary() {
        val plan = WordIngestor.plan(listOf(cand("L1", "غامضة", conf = 0.2), cand("L1", "", conf = 0.9)), emptyList())
        assertTrue(plan.toAdd.isEmpty())
        assertEquals(2, plan.reviews.size)
        assertTrue(plan.reviews[0].reasons.contains(ReviewReason.NO_TEXT))
    }

    @Test fun missingConfidenceNeedsReview() {
        val plan = WordIngestor.plan(listOf(cand("L1", "كلمة", conf = null)), emptyList())
        assertEquals(1, plan.reviews.size)
    }

    @Test fun poorCopyDoesNotFloodReviewWhenGoodCopyExists() {
        val plan = WordIngestor.plan(listOf(cand("L1", "تعال", conf = 0.2), cand("L1", "تعال", conf = 0.9, s = 5000, e = 5400)), emptyList())
        assertEquals(1, plan.toAdd.size)
        assertTrue(plan.reviews.isEmpty())
    }

    // ---- الحالة 4 + 6: الرفع
    private class FakeStore(ids: List<String>) : UploadStore {
        val state = ids.associateWith { UploadState.PENDING }.toMutableMap()
        val attempts = ids.associateWith { 0 }.toMutableMap()
        override fun pending(maxAttempts: Int) =
            state.filter { it.value != UploadState.DONE && attempts[it.key]!! < maxAttempts }.keys.map { UploadItem(it, attempts[it]!!) }
        override fun mark(id: String, state: UploadState, error: String?) {
            this.state[id] = state
            if (state == UploadState.FAILED) attempts[id] = attempts[id]!! + 1
        }
    }

    @Test fun case4_failedUploadKeepsSuccessfulOnesAndRetriesOnlyFailed() {
        val store = FakeStore(listOf("a", "b", "c"))
        var bFails = true
        val calls = mutableListOf<String>()
        val up = ResumableUploader(store)
        val f: (UploadItem) -> Unit = { calls += it.id; if (it.id == "b" && bFails) throw RuntimeException("500") }
        val s1 = up.run(upload = f)
        assertEquals(2, s1.done); assertEquals(1, s1.failed)
        assertEquals(UploadState.DONE, store.state["a"])
        bFails = false; calls.clear()
        val s2 = up.run(upload = f)
        assertEquals(listOf("b"), calls)
        assertEquals(1, s2.done)
    }

    @Test fun case6_offlineStopsAndKeepsPending() {
        val store = FakeStore(listOf("a", "b"))
        val s = ResumableUploader(store).run { if (it.id == "a") throw NetworkUnavailableException() }
        assertTrue(s.stoppedOffline)
        assertEquals(UploadState.PENDING, store.state["a"])
        assertEquals(0, store.attempts["a"])
    }

    // ---- الحالة 8
    @Test fun case8_failedReplacementKeepsOldFile() {
        val dir = tmp()
        val f = File(dir, "w.wav").apply { writeText("OLD") }
        try { AtomicFileReplace.replace(f) { throw RuntimeException("disk") } } catch (_: RuntimeException) {}
        assertEquals("OLD", f.readText())
        AtomicFileReplace.replace(f) { it.writeText("NEW") }
        assertEquals("NEW", f.readText())
        assertEquals(listOf("w.wav"), dir.list()!!.toList())
    }

    @Test fun emptyReplacementRejected() {
        val f = File(tmp(), "w.wav").apply { writeText("OLD") }
        try { AtomicFileReplace.replace(f) { it.writeBytes(ByteArray(0)) }; fail() } catch (_: IllegalArgumentException) {}
        assertEquals("OLD", f.readText())
    }

    // ---- WAV والقص
    private fun sine(ms: Int, sr: Int, hz: Double, amp: Double) =
        ShortArray(ms * sr / 1000) { (amp * 32767 * sin(2 * PI * hz * it / sr)).toInt().toShort() }

    private fun signalFile(): Pair<File, Int> {
        val sr = 16000
        val sil = ShortArray(sr) // 1s صمت
        val word = sine(400, sr, 300.0, 0.5)
        val all = sil + word + sil
        val f = File(tmp(), "s.wav")
        WavWriter.write(f, all, sr)
        return f to sr
    }

    @Test fun wavRoundTrip() {
        val (f, sr) = signalFile()
        val w = WavFile.open(f)
        assertEquals(sr, w.sampleRate)
        assertEquals(2400L, w.durationMs)
    }

    @Test fun clipTrimsSilenceButKeepsWholeWord() {
        val (f, _) = signalFile()
        val w = WavFile.open(f)
        // حدود تقريبية واسعة حول الكلمة (1000..1400ms)
        val r = WordClipper.clip(w, 900, 1500)
        assertTrue("طويل: ${r.durationMs}", r.durationMs in 400..500)
        assertTrue(r.startMs in 960..1000)
        assertTrue(r.endMs in 1400..1440)
        val q = r.quality()
        assertTrue(q.score > 0.5)
    }

    @Test fun silentClipScoresZero() {
        assertEquals(0.0, ClipQuality.analyze(ShortArray(1600), 16000).score, 0.0)
    }

    @Test fun streamingWriterProducesValidWav() {
        val f = File(tmp(), "st.wav")
        WavWriter.Streaming(f, 8000).use { it.write(ShortArray(800) { 1000 }); it.write(ShortArray(800) { -1000 }) }
        val w = WavFile.open(f)
        assertEquals(1600L, w.totalFrames)
        assertEquals(1000, w.readMono(0, 1)[0].toInt())
    }

    @Test fun chunkPlannerCutsInSilence() {
        val sr = 8000
        fun tone(ms: Int) = sine(ms, sr, 200.0, 0.4)
        val gap1 = ShortArray(sr / 5) // 200ms
        val gap2 = ShortArray(sr / 5)
        val all = tone(1000) + gap1 + tone(1000) + gap2 + tone(1000)
        val f = File(tmp(), "c.wav"); WavWriter.write(f, all, sr)
        val w = WavFile.open(f)
        val chunks = ChunkPlanner.plan(w, targetChunkMs = 1100, searchWindowMs = 600, overlapMs = 100)
        assertTrue(chunks.size >= 2)
        assertEquals(0L, chunks.first().startMs)
        assertEquals(w.durationMs, chunks.last().endMs)
        val firstCut = chunks[0].endMs - 50
        assertTrue("cut=$firstCut", firstCut in 1000..1200)
        for (i in 1 until chunks.size) assertTrue(chunks[i].startMs < chunks[i - 1].endMs)
    }

    // ---- التصدير
    @Test fun zipExportStructureAndIntegrity() {
        val dir = tmp()
        val sr = 16000
        fun mk(name: String): ExportWord {
            val f = File(dir, "$name.wav"); WavWriter.write(f, sine(300, sr, 250.0, 0.4), sr)
            return ExportWord(name, name, WordNormalizer.normalize(name), f, "rec.mp3", 0, 300, 300, Hashing.sha256(f), "APPROVED", 0.9)
        }
        val libs = listOf(
            ExportLibrary("l1", "المتحدث الأول", listOf(mk("أحمد"), mk("تعال"))),
            ExportLibrary("l2", "المتحدث الأول", listOf(mk("أحمد"))), // اسم مجلد مكرر
        )
        val bos = ByteArrayOutputStream()
        assertEquals(3, ZipExporter.export(libs, bos))
        val names = mutableMapOf<String, ByteArray>()
        ZipInputStream(bos.toByteArray().inputStream(), Charsets.UTF_8).use { z ->
            while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes() }
        }
        assertTrue(names.containsKey("المتحدث الأول/أحمد.wav"))
        assertTrue(names.containsKey("المتحدث الأول_2/أحمد.wav"))
        assertTrue(names.containsKey("metadata.json") && names.containsKey("README.txt"))
        val wavBytes = names["المتحدث الأول/تعال.wav"]!!
        assertEquals(Hashing.sha256(File(dir, "تعال.wav")), Hashing.sha256(wavBytes))
        val extracted = File(dir, "x.wav").apply { writeBytes(wavBytes) }
        assertEquals(300L, WavFile.open(extracted).durationMs)
    }

    @Test fun fileNamesSanitized() {
        assertEquals("a_b", FileNames.sanitize("a/b"))
        assertEquals("_", FileNames.sanitize("..."))
    }

    // ---- الدمج والمطابقة
    @Test fun mergePlanReportsConflicts() {
        val t = listOf(WordRef("t1", "أحمد", 0.5), WordRef("t2", "تعال", 0.9))
        val s = listOf(WordRef("s1", "أحمد", 0.8), WordRef("s2", "افتح", 0.7))
        val p = LibraryMergePlanner.plan(t, s)
        assertEquals(listOf("s2"), p.movable)
        assertEquals(1, p.conflicts.size)
        val best = LibraryMergePlanner.resolve(p, t, s, ConflictResolution.KEEP_BEST)
        assertEquals(listOf("t1"), best.replaceTargetIds)
        assertTrue(best.moveIds.containsAll(listOf("s1", "s2")))
        val keep = LibraryMergePlanner.resolve(p, t, s, ConflictResolution.KEEP_TARGET)
        assertEquals(listOf("s1"), keep.deleteSourceIds)
    }

    @Test fun voiceMatcher() {
        val libs = mapOf("a" to floatArrayOf(1f, 0f), "b" to floatArrayOf(0f, 1f))
        assertEquals("a", VoiceMatcher.bestMatch(floatArrayOf(0.9f, 0.1f), libs))
        assertNull(VoiceMatcher.bestMatch(floatArrayOf(1f, 1f), mapOf("a" to floatArrayOf(1f, -1f))))
    }

    @Test fun resampleLength() {
        val r = Resample.linear(ShortArray(44100) { 1000 }, 44100, 48000)
        assertEquals(48000, r.size)
        assertEquals(1000, r[100].toInt())
    }

    @Test fun speakerAssignment() {
        val words = listOf(TimedWord("a", 0, 500, .9), TimedWord("b", 1000, 1500, .9), TimedWord("c", 5000, 5400, .9))
        val turns = listOf(SpeakerTurn(0, 900, "S0"), SpeakerTurn(800, 2000, "S1"), SpeakerTurn(1000, 1400, "S0"))
        val r = SpeakerAssigner.assign(words, turns)
        assertEquals("S0", r[0].speaker)
        assertEquals("S1", r[1].speaker)
        assertTrue(r[1].overlap > 0.5)
        assertNull(r[2].speaker)
    }
}
