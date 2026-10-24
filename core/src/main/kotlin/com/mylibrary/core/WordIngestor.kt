package com.mylibrary.core

data class IngestPolicy(
    val minConfidence: Double = 0.55,
    val minQuality: Double = 0.30,
    val maxOverlapRatio: Double = 0.25,
    val minDurationMs: Long = 60,
    val maxDurationMs: Long = 4000,
    /** الفرق في الجودة اللازم لاقتراح استبدال مقطع موجود (الاستبدال لا يتم تلقائيًا أبدًا). */
    val replaceMargin: Double = 0.15,
    val requireConfidence: Boolean = true,
)

enum class ReviewReason { NO_TEXT, NO_CONFIDENCE, LOW_CONFIDENCE, LOW_QUALITY, OVERLAP, TOO_SHORT, TOO_LONG }

data class PlannedAdd(val candidate: WordCandidate, val normalized: String)
data class PlannedReview(val candidate: WordCandidate, val normalized: String, val reasons: List<ReviewReason>)
data class ReplaceSuggestion(val existingWordId: String, val candidate: WordCandidate, val normalized: String)

data class IngestPlan(
    val toAdd: List<PlannedAdd>,
    val reviews: List<PlannedReview>,
    val replaceSuggestions: List<ReplaceSuggestion>,
    val duplicatesSkipped: Int,
)

/**
 * قلب قاعدة «ملف واحد لكل كلمة فريدة داخل مكتبة المتحدث نفسه».
 * دالة نقية بلا إدخال/إخراج، ليسهل اختبارها؛ تستخدمها طبقة Room ثم تُنفَّذ نتيجتها في معاملة واحدة.
 */
object WordIngestor {

    fun score(c: WordCandidate): Double =
        c.quality * 0.6 + (c.asrConfidence ?: 0.5) * 0.3 + (1.0 - c.overlapRatio) * 0.1

    fun evaluate(c: WordCandidate, normalized: String, p: IngestPolicy): List<ReviewReason> {
        val r = mutableListOf<ReviewReason>()
        if (normalized.isEmpty()) r += ReviewReason.NO_TEXT
        val conf = c.asrConfidence
        if (conf == null) {
            if (p.requireConfidence) r += ReviewReason.NO_CONFIDENCE
        } else if (conf < p.minConfidence) r += ReviewReason.LOW_CONFIDENCE
        if (c.quality < p.minQuality) r += ReviewReason.LOW_QUALITY
        if (c.overlapRatio > p.maxOverlapRatio) r += ReviewReason.OVERLAP
        if (c.durationMs < p.minDurationMs) r += ReviewReason.TOO_SHORT
        if (c.durationMs > p.maxDurationMs) r += ReviewReason.TOO_LONG
        return r
    }

    fun plan(
        candidates: List<WordCandidate>,
        existing: Collection<ExistingWord>,
        policy: IngestPolicy = IngestPolicy(),
    ): IngestPlan {
        val existingByKey = existing.associateBy { it.libraryId to it.normalized }
        val good = LinkedHashMap<Pair<String, String>, MutableList<WordCandidate>>()
        val bad = LinkedHashMap<Pair<String, String>, MutableList<Pair<WordCandidate, List<ReviewReason>>>>()
        val reviews = mutableListOf<PlannedReview>()

        for (c in candidates) {
            val key = WordNormalizer.normalize(c.text)
            val reasons = evaluate(c, key, policy)
            when {
                // نص غير صالح لا يمكن إزالة تكراره: يذهب للمراجعة دائمًا، ولا نخترع له نصًا.
                key.isEmpty() -> reviews += PlannedReview(c, key, reasons)
                reasons.isEmpty() -> good.getOrPut(c.libraryId to key) { mutableListOf() } += c
                else -> bad.getOrPut(c.libraryId to key) { mutableListOf() } += c to reasons
            }
        }

        var duplicates = 0
        val adds = mutableListOf<PlannedAdd>()
        val suggestions = mutableListOf<ReplaceSuggestion>()

        for ((key, list) in good) {
            val best = list.maxByOrNull { score(it) }!!
            duplicates += list.size - 1
            val ex = existingByKey[key]
            if (ex == null) {
                adds += PlannedAdd(best, key.second)
            } else {
                duplicates += 1
                if (!ex.userLocked && best.quality - ex.quality > policy.replaceMargin) {
                    suggestions += ReplaceSuggestion(ex.id, best, key.second)
                }
            }
        }

        for ((key, list) in bad) {
            if (good.containsKey(key) || existingByKey.containsKey(key)) {
                // توجد نسخة مقبولة (أو محفوظة) من الكلمة؛ لا حاجة لإغراق المراجعة.
                duplicates += list.size
            } else {
                val (c, reasons) = list.maxByOrNull { score(it.first) }!!
                reviews += PlannedReview(c, key.second, reasons)
                duplicates += list.size - 1
            }
        }
        return IngestPlan(adds, reviews, suggestions, duplicates)
    }
}
