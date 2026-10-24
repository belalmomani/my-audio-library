package com.mylibrary.app.audio

import com.mylibrary.core.WavFile
import kotlin.math.abs

object Waveform {
    /** قيم قصوى مطبَّعة (0..1) لـ [buckets] شريحة من المدى المحدد. */
    fun peaks(wav: WavFile, startMs: Long, endMs: Long, buckets: Int): FloatArray {
        val s = wav.msToFrame(startMs)
        val e = wav.msToFrame(endMs)
        val total = (e - s).toInt()
        if (total <= 0 || buckets <= 0) return FloatArray(0)
        val data = wav.readMono(s, total)
        val out = FloatArray(buckets)
        val per = (data.size.toDouble() / buckets).coerceAtLeast(1.0)
        var globalMax = 1
        for (b in 0 until buckets) {
            val from = (b * per).toInt()
            val to = minOf(data.size, ((b + 1) * per).toInt().coerceAtLeast(from + 1))
            var m = 0
            for (i in from until to) m = maxOf(m, abs(data[i].toInt()))
            out[b] = m.toFloat()
            globalMax = maxOf(globalMax, m)
        }
        for (i in out.indices) out[i] = out[i] / globalMax
        return out
    }
}
