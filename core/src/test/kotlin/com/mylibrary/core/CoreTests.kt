package com.mylibrary.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.sin

private fun cand(lib: String, text: String, q: Double = 0.8, conf: Double? = 0.9, s: Long = 0, e: Long = s + 400, src: String = "s1", id: String = "$lib-$text-$s") =
    WordCandidate(id, lib, text, s, e, conf, q, 0.0, src)

private fun tone(ms: Int, sr: Int = 16000, amp: Double = 0.5, hz: Double = 300.0) =
    ShortArray(ms * sr / 1000) { (sin(2 * PI * hz * it / sr) * amp * 32767).toInt().toShort() }

class NormalizerTest {
    @Test fun punctuationIsIgnored() {
        val k = WordNormalizer.normalize("أحمد")
        assertEquals(k, WordNormalizer.normalize("أحمد،"))
        assertEquals(k, WordNormalizer.normalize("أحمد!"))
        assertEquals(k, WordNormalizer.normalize("«أحمد»"))
    }
    @Test fun differentWordsStayDifferent() {
        assertNotEquals(WordNormalizer.normalize("ذهب"), WordNormalizer.normalize("يذهب"))
        assertNotEquals(WordNormalizer.normalize("الكتاب"), WordNormalizer.normalize("كتاب"))
    }
    @Test fun diacriticsAndTatweelStripped() {
        assertEquals(WordNormalizer.normalize("كتاب"), WordNormalizer.normalize("كِتَابٌ"))
        assertEquals(WordNormalizer.normalize("محمد"), WordNormalizer.normalize("مـحـمـد"))
    }
    @Test fun latinLowercased() = assertEquals("hello", WordNormalizer.normalize("Hello!"))
    @Test fun emptyIsUnusable() = assertFalse(WordNormalizer.isUsable("،!؟"))
}

class IngestTests {
    // الحالة 1
    @Test fun sameSpeakerRepeatedWordKeepsOne() {
        val plan = WordIngestor.plan(
            listOf(cand("L1", "أحمد", s = 0), cand("L1", "تعال", s = 500), cand("L1", "أحمد،", s = 1000), cand("L1", "اذهب", s = 1500)),
            emptyList(),
        )
        assertEquals(setOf("أحمد", "تعال", "اذهب"), plan.toAdd.map { it.normalized }.toSet())
        assertEquals(3, plan.toAdd.size)
        assertEquals(1, plan.duplicatesSkipped)
    }
    // الحالة 2
    @Test fun differentSpeakersEachKeepTheirOwn() {
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد"), cand("L2", "أحمد")), emptyList())
        assertEquals(2, plan.toAdd.size)
        assertEquals(setOf("L1", "L2"), plan.toAdd.map { it.candidate.libraryId }.toSet())
    }
    // الحالة 3
    @Test fun secondRecordingAddsOnlyNewWords() {
        val existing = listOf(ExistingWord("w1", "L1", WordNormalizer.normalize("أحمد"), 0.8))
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد"), cand("L1", "افتح")), existing)
        assertEquals(listOf("افتح"), plan.toAdd.map { it.normalized })
        assertEquals(1, plan.duplicatesSkipped)
        assertTrue(plan.replaceSuggestions.isEmpty())
    }
    @Test fun betterClipIsSuggestedNeverAutoReplaced() {
        val existing = listOf(ExistingWord("w1", "L1", "أحمد", 0.3))
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد", q = 0.9)), existing)
        assertTrue(plan.toAdd.isEmpty())
        assertEquals("w1", plan.replaceSuggestions.single().existingWordId)
        val locked = WordIngestor.plan(listOf(cand("L1", "أحمد", q = 0.9)), listOf(existing[0].copy(userLocked = true)))
        assertTrue(locked.replaceSuggestions.isEmpty())
    }
    @Test fun bestClipOfRepeatsIsChosen() {
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد", q = 0.5, id = "a"), cand("L1", "أحمد", q = 0.9, id = "b")), emptyList())
        assertEquals("b", plan.toAdd.single().candidate.id)
    }
    // الحالة 5
    @Test fun unclearWordGoesToReviewWithoutInventingText() {
        val plan = WordIngestor.plan(listOf(cand("L1", "؟؟", conf = 0.2), cand("L1", "شيء", conf = 0.3), cand("L1", "جيد")), emptyList())
        assertEquals(listOf("جيد"), plan.toAdd.map { it.normalized })
        assertEquals(2, plan.reviews.size)
        assertTrue(plan.reviews.any { it.reasons.contains(ReviewReason.NO_TEXT) })
        assertTrue(plan.reviews.any { it.reasons.contains(ReviewReason.LOW_CONFIDENCE) })
    }
    @Test fun missingConfidenceNeedsReview() {
        val plan = WordIngestor.plan(listOf(cand("L1", "كلمة", conf = null)), emptyList())
        assertTrue(plan.toAdd.isEmpty()); assertEquals(1, plan.reviews.size)
    }
    @Test fun poorDuplicateDoesNotFloodReview() {
        val plan = WordIngestor.plan(listOf(cand("L1", "أحمد"), cand("L1", "أحمد", conf = 0.1)), emptyList())
        assertEquals(1, plan.toAdd.size); assertTrue(plan.reviews.isEmpty()); assertEquals(1, plan.duplicatesSkipped)
    }
}

class UploadTests {
    private class Store(vararg ids: String) : UploadStore {
        val state = ids.associateWith { UploadState.PENDING }.toMutableMap()
        val attempts = ids.associateWith { 0 }.toMutableMap()
        override fun pending(maxAttempts: Int) = state.filter { (it.value == UploadState.PENDING || it.value == UploadState.FAILED) && attempts[it.key]!! < maxAttempts }
            .keys.map { UploadItem(it, attempts[it]!!) }
        override fun mark(id: String, state: UploadState, error: String?) {
            this.state[id] = state
            if (state == UploadState.FAILED) attempts[id] = attempts[id]!! + 1
        }
    }
    // الحالة 4
    @Test fun failedFileDoesNotLoseSuccessfulOnesAndIsRetried() {
        val store = Store("a", "b", "c")
        val up = ResumableUploader(store)
        val uploaded = mutableListOf<String>()
        var failB = true
        val s1 = up.run { if (it.id == "b" && failB) error("boom") else uploaded += it.id }
        assertEquals(2, s1.done); assertEquals(1, s1.failed)
        failB = false
        val s2 = up.run { uploaded += it.id }
        assertEquals(1, s2.done)
        assertEquals(listOf("a", "c", "b"), uploaded)       // لا إعادة رفع لما نجح
        assertTrue(store.state.values.all { it == UploadState.DONE })
    }
    // الحالة 6
    @Test fun offlineStopsCleanlyAndKeepsPending() {
        val store = Store("a", "b", "c")
        val s = ResumableUploader(store).run { if (it.id == "b") throw NetworkUnavailableException() }
        assertTrue(s.stoppedOffline); assertEquals(1, s.done); assertEquals(0, s.failed)
        assertEquals(UploadState.DONE, store.state["a"])
        assertEquals(UploadState.PENDING, store.state["b"]); assertEquals(UploadState.PENDING, store.state["c"])
    }
}

class FileTests {
    // الحالة 8
    @Test fun failedReplacementKeepsOldFile() {
        val dir = Files.createTempDirectory("rep").toFile()
        val f = File(dir, "w.wav").also { it.writeText("OLD") }
        assertThrows(IllegalStateException::class.java) { AtomicFileReplace.replace(f) { error("disk full") } }
        assertEquals("OLD", f.readText())
        assertThrows(IllegalArgumentException::class.java) { AtomicFileReplace.replace(f) { it.writeBytes(ByteArray(0)) } }
        assertEquals("OLD", f.readText())
        AtomicFileReplace.replace(f) { it.writeText("NEW") }
        assertEquals("NEW", f.readText())
        assertFalse(File(dir, "w.wav.bak").exists())
    }

    @Test fun zipExportIsOrganizedAndAudioIntact() {
        val dir = Files.createTempDirectory("zip").toFile()
        fun wav(name: String, hz: Double): File = File(dir, name).also { WavWriter.write(it, tone(200, hz = hz), 16000) }
        val a = wav("a.wav", 300.0); val b = wav("b.wav", 500.0)
        fun w(id: String, t: String, f: File) = ExportWord(id, t, WordNormalizer.normalize(t), f, "rec.mp3", 0, 200, 200, Hashing.sha256(f), "APPROVED", 0.9)
        val bos = ByteArrayOutputStream()
        val n = ZipExporter.export(listOf(
            ExportLibrary("L1", "المتحدث الأول", listOf(w("1", "أحمد", a))),
            ExportLibrary("L2", "المتحدث الثاني", listOf(w("2", "أحمد", b))),
        ), bos)
        assertEquals(2, n)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bos.toByteArray()), Charsets.UTF_8).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        assertTrue(entries.containsKey("المتحدث الأول/أحمد.wav"))
        assertTrue(entries.containsKey("المتحدث الثاني/أحمد.wav"))
        assertTrue(entries.containsKey("metadata.json") && entries.containsKey("README.txt"))
        assertEquals(Hashing.sha256(a), Hashing.sha256(entries["المتحدث الأول/أحمد.wav"]!!))
        val out = File(dir, "x.wav").also { it.writeBytes(entries["المتحدث الثاني/أحمد.wav"]!!) }
        assertEquals(3200, WavFile.open(out).totalFrames.toInt())   // الملف المفكوك صالح
        assertTrue(String(entries["metadata.json"]!!, Charsets.UTF_8).contains("\"sha256\""))
    }

    @Test fun mergePlanReportsConflicts() {
        val t = listOf(WordRef("t1", "أحمد", 0.5), WordRef("t2", "تعال", 0.5))
        val s = listOf(WordRef("s1", "أحمد", 0.9), WordRef("s2", "افتح", 0.5))
        val p = LibraryMergePlanner.plan(t, s)
        assertEquals(listOf("s2"), p.movable); assertEquals(1, p.conflicts.size)
        val r = LibraryMergePlanner.resolve(p, t, s, ConflictResolution.KEEP_BEST)
        assertEquals(listOf("t1"), r.replaceTargetIds); assertTrue(r.moveIds.containsAll(listOf("s1", "s2")))
        val k = LibraryMergePlanner.resolve(p, t, s, ConflictResolution.KEEP_TARGET)
        assertEquals(listOf("s1"), k.deleteSourceIds)
    }
}

class AudioTests {
    private fun makeWav(): Pair<File, WavFile> {
        // 3 ثوانٍ: صمت 500ms، كلمة (نغمة) 500..900ms، صمت، ثم نغمة 2000..2400
        val sr = 16000
        val s = ShortArray(3 * sr)
        tone(400).copyInto(s, 500 * sr / 1000)
        tone(400).copyInto(s, 2000 * sr / 1000)
        val f = File.createTempFile("tst_"+"t", ".wav").also { WavWriter.write(it, s, sr) }
        return f to WavFile.open(f)
    }

    @Test fun wavRoundTrip() {
        val (_, w) = makeWav()
        assertEquals(16000, w.sampleRate); assertEquals(3000L, w.durationMs)
    }
    @Test fun clipTrimsSilenceButKeepsWholeWord() {
        val (_, w) = makeWav()
        val c = WordClipper.clip(w, 400, 1000)       // حدود واسعة فيها صمت
        assertTrue("start=${c.startMs}", c.startMs in 440..500)
        assertTrue("end=${c.endMs}", c.endMs in 900..960)
        assertTrue(c.durationMs in 400..520)
        val q = ClipQuality.analyze(c.samples, c.sampleRate)
        assertTrue(q.score > 0.3)
    }
    @Test fun silentClipScoresZero() = assertEquals(0.0, ClipQuality.analyze(ShortArray(3200), 16000).score, 0.0)
    @Test fun noisyClipScoresLowerThanClean() {
        val clean = ShortArray(8000) { if (it in 2000..6000) tone(1)[0] else 0 }
        val base = tone(250)
        val padded = ShortArray(16000).also { base.copyInto(it, 4000) }
        val rnd = java.util.Random(1)
        val noisy = ShortArray(16000) { (padded[it] + rnd.nextGaussian() * 6000).toInt().coerceIn(-32768, 32767).toShort() }
        assertTrue(ClipQuality.analyze(padded, 16000).score > ClipQuality.analyze(noisy, 16000).score)
        assertNotNull(clean)
    }
    @Test fun chunksCutInSilenceAndCoverEverything() {
        val sr = 16000
        val s = tone(6000, sr)                          // 6 ثوانٍ متواصلة
        for (i in (2900 * sr / 1000) until (3100 * sr / 1000)) s[i] = 0   // فجوة صمت حول 3 ثوانٍ
        val f = File.createTempFile("tst_"+"c", ".wav").also { WavWriter.write(it, s, sr) }
        val chunks = ChunkPlanner.plan(WavFile.open(f), targetChunkMs = 3000, searchWindowMs = 1000, overlapMs = 200)
        assertEquals(2, chunks.size)
        assertEquals(0L, chunks.first().startMs); assertEquals(6000L, chunks.last().endMs)
        assertTrue(chunks[0].endMs in 2900..3200)
        assertTrue(chunks[1].startMs < chunks[0].endMs)  // تراكب
    }
    @Test fun streamingWriterProducesValidFile() {
        val f = File.createTempFile("tst_"+"s", ".wav")
        WavWriter.Streaming(f, 16000).use { it.write(tone(100)); it.write(tone(100)) }
        assertEquals(3200L, WavFile.open(f).totalFrames)
    }
}
