package com.mylibrary.core

enum class ReviewStatus { APPROVED, NEEDS_REVIEW, REJECTED }

enum class JobStage {
    QUEUED,
    EXTRACTING_AUDIO,
    SEPARATING,
    DIARIZING,
    TRANSCRIBING,
    AWAITING_SPEAKER_REVIEW,
    EXTRACTING_WORDS,
    UPLOADING,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    FAILED,
    CANCELED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == COMPLETED_WITH_WARNINGS || this == FAILED || this == CANCELED
}

/** كلمة مرشّحة مستخرجة من تسجيل، قبل اتخاذ قرار الإضافة. */
data class WordCandidate(
    val id: String,
    val libraryId: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** ثقة نموذج التعرف (0..1) أو null إن لم تتوفر. */
    val asrConfidence: Double?,
    /** جودة المقطع (0..1) من [ClipQuality]. */
    val quality: Double,
    /** نسبة التداخل مع كلام متحدث آخر (0..1). */
    val overlapRatio: Double = 0.0,
    val sourceId: String,
) {
    val durationMs: Long get() = endMs - startMs
}

/** كلمة محفوظة فعلًا في مكتبة. */
data class ExistingWord(
    val id: String,
    val libraryId: String,
    val normalized: String,
    val quality: Double,
    /** true إذا استبدلها المستخدم يدويًا؛ فلا يُقترح استبدالها تلقائيًا. */
    val userLocked: Boolean = false,
)
