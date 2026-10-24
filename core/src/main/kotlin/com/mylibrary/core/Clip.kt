package com.mylibrary.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

data class QualityReport(
    val score: Double,
    val snrDb: Double,
    val clippingRatio: Double,
    val edgeEnergy: Double,
    val issues: List<String>,
)

/** تقدير جودة مقطع كلمة (تقريبي؛ لا يغني عن الاستماع). */
object ClipQuality {
    /** [noiseRms]: مستوى الضجيج المقاس من الصوت المحيط قبل القص (0..1)؛ إن غاب يُقدَّر من المقطع نفسه. */
    fun analyze(samples: ShortArray, sampleRate: Int, noiseRms: Double? = null): QualityReport {
        if (samples.isEmpty()) return QualityReport(0.0, 0.0, 0.0, 1.0, listOf("مقطع فارغ"))
        val frame = (sampleRate / 100).coerceAtLeast(1)
        val frames = (samples.size / frame).coerceAtLeast(1)
        val rms = DoubleArray(frames) { f ->
            val from = f * frame
            val to = minOf(samples.size, from + frame)
            var acc = 0.0
            for (i in from until to) {
                val v = samples[i] / 32768.0
                acc += v * v
            }
            sqrt(acc / (to - from).coerceAtLeast(1))
        }
        val peak = rms.max()
        if (peak < 0.003) return QualityReport(0.0, 0.0, 0.0, 1.0, listOf("المقطع صامت تقريبًا"))
        val sorted = rms.sortedArray()
        val noise = (noiseRms ?: sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]).coerceAtLeast(1e-5)
        val snr = 20 * log10(peak / noise)
        val clipped = samples.count { abs(it.toInt()) >= 32700 }.toDouble() / samples.size
        val edge = maxOf(rms.first(), rms.last()) / peak

        val snrScore = ((snr - 5.0) / 30.0).coerceIn(0.0, 1.0)
        val clipScore = 1.0 - (clipped * 50.0).coerceIn(0.0, 1.0)
        val edgeScore = 1.0 - (edge / 0.6).coerceIn(0.0, 1.0)
        val score = 0.5 * snrScore + 0.2 * clipScore + 0.3 * edgeScore

        val issues = mutableListOf<String>()
        if (snr < 12) issues += "ضوضاء مرتفعة"
        if (clipped > 0.01) issues += "تشبّع/تقطّع في الصوت"
        if (edge > 0.5) issues += "قد تكون الكلمة مقصوصة من أحد الطرفين"
        return QualityReport(score, snr, clipped, edge, issues)
    }
}

data class ClipResult(val samples: ShortArray, val sampleRate: Int, val startMs: Long, val endMs: Long, val noiseRms: Double? = null) {
    fun quality(): QualityReport = ClipQuality.analyze(samples, sampleRate, noiseRms)
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/**
 * قص مقطع الكلمة من المصدر الأصلي (دون توليد أي صوت جديد):
 * 1) توسيع الحدود بهامش أمان، 2) إزالة الصمت الزائد حول الكلمة بعتبة منخفضة جدًا
 * كي لا تُقطع الأحرف الضعيفة (س، ف، هـ)، 3) تلاشٍ قصير جدًا لتفادي الفرقعة.
 */
object WordClipper {
    fun clip(
        wav: WavFile,
        startMs: Long,
        endMs: Long,
        padMs: Long = 40,
        trimThresholdDb: Double = -45.0,
        keepMs: Long = 20,
        fadeMs: Long = 3,
    ): ClipResult {
        require(endMs > startMs) { "نهاية الكلمة قبل بدايتها" }
        val s0 = (startMs - padMs).coerceAtLeast(0L)
        val e0 = (endMs + padMs).coerceAtMost(wav.durationMs)
        val raw = wav.readMonoMs(s0, e0)
        if (raw.isEmpty()) return ClipResult(raw, wav.sampleRate, s0, s0)
        val sr = wav.sampleRate
        val frame = (sr / 200).coerceAtLeast(1) // 5ms
        val frames = (raw.size + frame - 1) / frame
        val rms = DoubleArray(frames) { f ->
            val from = f * frame
            val to = minOf(raw.size, from + frame)
            var acc = 0.0
            for (i in from until to) {
                val v = raw[i] / 32768.0
                acc += v * v
            }
            sqrt(acc / (to - from))
        }
        val peak = rms.max()
        val noise = rms.sortedArray().let { it[(it.size * 0.1).toInt().coerceIn(0, it.size - 1)] }
        var from = 0
        var to = raw.size
        if (peak > 0.0) {
            val thr = peak * 10.0.pow(trimThresholdDb / 20.0)
            val first = rms.indexOfFirst { it >= thr }
            val last = rms.indexOfLast { it >= thr }
            val keep = (keepMs * sr / 1000L).toInt()
            from = (first * frame - keep).coerceAtLeast(0)
            to = ((last + 1) * frame + keep).coerceAtMost(raw.size)
        }
        val out = raw.copyOfRange(from, to)
        val fade = (fadeMs * sr / 1000L).toInt().coerceAtMost(out.size / 2)
        for (i in 0 until fade) {
            val g = i.toDouble() / fade
            out[i] = (out[i] * g).toInt().toShort()
            val j = out.size - 1 - i
            out[j] = (out[j] * g).toInt().toShort()
        }
        val newStart = s0 + from * 1000L / sr
        return ClipResult(out, sr, newStart, newStart + out.size * 1000L / sr, noise)
    }
}

data class Chunk(val index: Int, val startMs: Long, val endMs: Long)

/** تقسيم التسجيلات الطويلة عند أهدأ نقطة قرب الحد المستهدف مع تراكب صغير، كي لا تُقطع كلمة. */
object ChunkPlanner {
    fun plan(
        wav: WavFile,
        targetChunkMs: Long = 600_000,
        searchWindowMs: Long = 20_000,
        overlapMs: Long = 2_000,
        probeMs: Long = 100,
    ): List<Chunk> {
        val total = wav.durationMs
        if (total <= targetChunkMs + searchWindowMs) return listOf(Chunk(0, 0, total))
        val cuts = mutableListOf<Long>()
        var pos = 0L
        while (total - pos > targetChunkMs + searchWindowMs) {
            val ideal = pos + targetChunkMs
            val lo = (ideal - searchWindowMs / 2).coerceAtLeast(pos + probeMs)
            val hi = (ideal + searchWindowMs / 2).coerceAtMost(total - probeMs)
            var bestAt = ideal
            var bestRms = Double.MAX_VALUE
            var t = lo
            while (t <= hi) {
                val seg = wav.readMonoMs(t, t + probeMs)
                if (seg.isNotEmpty()) {
                    var acc = 0.0
                    for (v in seg) acc += (v / 32768.0).let { it * it }
                    val r = sqrt(acc / seg.size)
                    if (r < bestRms) { bestRms = r; bestAt = t + probeMs / 2 }
                }
                t += probeMs
            }
            cuts += bestAt
            pos = bestAt
        }
        val chunks = mutableListOf<Chunk>()
        var start = 0L
        for ((i, cut) in cuts.withIndex()) {
            chunks += Chunk(i, start, (cut + overlapMs / 2).coerceAtMost(total))
            start = (cut - overlapMs / 2).coerceAtLeast(0L)
        }
        chunks += Chunk(cuts.size, start, total)
        return chunks
    }
}

object Resample {
    /** تحويل معدل العينة بالاستيفاء الخطي (يكفي لتجهيز الترميز؛ لا يُستخدم على المقاطع المحفوظة). */
    fun linear(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * ratio
            val i0 = p.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = p - i0
            out[i] = (src[i0] * (1 - f) + src[i1] * f).toInt().toShort()
        }
        return out
    }
}
