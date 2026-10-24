package com.mylibrary.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val embeddingJson: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val uri: String,
    /** WAV أحادي مفكوك الترميز بمعدل المصدر (ملف عمل مؤقت). */
    val workPath: String? = null,
    val originalCopyPath: String? = null,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val createdAt: Long,
)

@Entity(tableName = "speakers", indices = [Index("sourceId"), Index("libraryId")])
data class SpeakerEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val jobId: String,
    /** وسم تقني من الخادم (SPEAKER_00...). */
    val label: String,
    val ordinal: Int,
    val libraryId: String? = null,
    val embeddingJson: String? = null,
)

@Entity(
    tableName = "jobs",
    indices = [Index("sourceId")],
)
data class JobEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val stage: String,
    val progress: Float = 0f,
    val cloud: Boolean = false,
    val separate: Boolean = false,
    val diarize: Boolean = true,
    val useProcessedAudio: Boolean = true,
    val language: String = "ar",
    val keepOriginal: Boolean = false,
    val serverJobId: String? = null,
    val serverSourceKey: String? = null,
    val processedPath: String? = null,
    val speakersFound: Int = 0,
    val wordsFound: Int = 0,
    val newWords: Int = 0,
    val duplicatesSkipped: Int = 0,
    val needsReview: Int = 0,
    val filesSaved: Int = 0,
    val errors: Int = 0,
    val lastError: String? = null,
    val warnings: String = "",
    val paused: Boolean = false,
    /** المرحلة التي فشلت عندها المهمة، لتُستأنف منها عند إعادة المحاولة. */
    val resumeStage: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

/** كلمة مرشّحة من التعرف، تُحفظ حتى تُعتمد المراجعة وتُعالج على دفعات (قابلة للاستئناف). */
@Entity(tableName = "candidates", indices = [Index("jobId")])
data class CandidateEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val sourceId: String,
    val speakerId: String?,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double?,
    val overlap: Double,
    /** null = لم تُقيَّم بعد. */
    val quality: Double? = null,
    val done: Boolean = false,
)

@Entity(
    tableName = "words",
    indices = [Index(value = ["libraryId", "normalized"], unique = true), Index("libraryId"), Index("uploadState")],
)
data class WordEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val speakerId: String? = null,
    val text: String,
    val normalized: String,
    val fileName: String,
    val localPath: String,
    val cloudKey: String? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val format: String = "wav",
    val sizeBytes: Long,
    val extractedAt: Long,
    val reviewStatus: String = "APPROVED",
    val quality: Double,
    val sha256: String,
    val processingVersion: String,
    val userLocked: Boolean = false,
    val uploadState: String = "PENDING",
    val uploadAttempts: Int = 0,
    val lastError: String? = null,
)

/** عنصر بانتظار مراجعة المستخدم: كلمة غير موثوقة، أو اقتراح استبدال مقطع أفضل. */
@Entity(tableName = "review_items", indices = [Index("libraryId")])
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val jobId: String?,
    val libraryId: String?,
    val sourceId: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val reason: String,
    val confidence: Double?,
    val quality: Double?,
    /** إن كان اقتراح استبدال: الكلمة الموجودة. */
    val targetWordId: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entity: String,
    val entityId: String?,
    val action: String,
    val detail: String = "",
    val at: Long,
)

// ---- نتائج استعلامات (ليست جداول)
data class LibrarySummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val wordCount: Int,
    val sizeBytes: Long,
)

data class ExistingRow(val id: String, val libraryId: String, val normalized: String, val quality: Double, val userLocked: Boolean)
data class UploadRow(val id: String, val uploadAttempts: Int)
data class Totals(val libraries: Int, val words: Int, val bytes: Long)
