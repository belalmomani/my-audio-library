package com.mylibrary.core

data class SpeakerTurn(val startMs: Long, val endMs: Long, val label: String)
data class TimedWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Double?)
data class AssignedWord(val word: TimedWord, val speaker: String?, val overlap: Double)

/** إسناد الكلمات إلى المتحدثين (مطابق لمنطق server/app/pipeline/assign.py). */
object SpeakerAssigner {
    fun assign(words: List<TimedWord>, turns: List<SpeakerTurn>): List<AssignedWord> = words.map { w ->
        val dur = (w.endMs - w.startMs).coerceAtLeast(1L).toDouble()
        val cover = HashMap<String, Long>()
        for (t in turns) {
            if (t.startMs >= w.endMs) continue
            val ov = minOf(w.endMs, t.endMs) - maxOf(w.startMs, t.startMs)
            if (ov > 0) cover[t.label] = (cover[t.label] ?: 0L) + ov
        }
        val best = cover.maxByOrNull { it.value }
        val other = cover.filterKeys { it != best?.key }.values.maxOrNull() ?: 0L
        AssignedWord(w, if (best != null && best.value / dur >= 0.5) best.key else null, minOf(1.0, other / dur))
    }
}
