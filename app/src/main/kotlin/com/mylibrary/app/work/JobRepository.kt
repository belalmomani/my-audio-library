package com.mylibrary.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.*
import com.mylibrary.app.cloud.BackendApi
import com.mylibrary.app.data.*
import com.mylibrary.core.JobStage
import java.io.File
import java.util.UUID

data class ImportOptions(
    val cloud: Boolean,
    val separate: Boolean,
    val diarize: Boolean,
    val keepOriginal: Boolean,
    val language: String = "ar",
)

data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long)

class JobRepository(
    private val ctx: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val backend: BackendApi,
) {
    private val wm get() = WorkManager.getInstance(ctx)

    fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment ?: "تسجيل"
        var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        return PickedFile(uri, name, size)
    }

    fun persistRead(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    suspend fun start(files: List<PickedFile>, o: ImportOptions): List<String> {
        val ids = mutableListOf<String>()
        for (f in files) {
            val sid = UUID.randomUUID().toString()
            val jid = UUID.randomUUID().toString()
            var original: String? = null
            if (o.keepOriginal) {
                val dst = File(ctx.filesDir, "originals/$sid-${f.name}".replace("/", "_")).also { it.parentFile?.mkdirs() }
                ctx.contentResolver.openInputStream(f.uri)?.use { i -> dst.outputStream().use { i.copyTo(it) } }
                original = dst.absolutePath
            }
            db.sources().upsert(SourceEntity(sid, f.name, f.uri.toString(), null, original, 0, f.sizeBytes, System.currentTimeMillis()))
            db.jobs().upsert(JobEntity(
                id = jid, sourceId = sid, stage = JobStage.QUEUED.name, cloud = o.cloud, separate = o.separate,
                diarize = o.diarize, language = o.language, keepOriginal = o.keepOriginal, startedAt = System.currentTimeMillis(),
            ))
            enqueue(jid, "PROCESS", o.cloud)
            ids += jid
        }
        return ids
    }

    fun enqueue(jobId: String, phase: String, cloud: Boolean) {
        val req = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInputData(workDataOf("jobId" to jobId, "phase" to phase))
            .setConstraints(Constraints.Builder().apply { if (cloud) setRequiredNetworkType(NetworkType.CONNECTED) }.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("job")
            .build()
        wm.enqueueUniqueWork("job-$jobId", ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun pause(jobId: String) {
        db.jobs().setPaused(jobId, true)
        wm.cancelUniqueWork("job-$jobId")
    }

    suspend fun resume(jobId: String) {
        val j = db.jobs().get(jobId) ?: return
        db.jobs().setPaused(jobId, false)
        when (j.stage) {
            JobStage.AWAITING_SPEAKER_REVIEW.name -> return
            JobStage.EXTRACTING_WORDS.name, JobStage.UPLOADING.name ->
                enqueue(jobId, if (j.serverJobId != null && j.stage == JobStage.UPLOADING.name && db.candidates().count(jobId) == 0) "PROCESS" else "EXTRACT", j.cloud)
            else -> enqueue(jobId, "PROCESS", j.cloud)
        }
    }

    suspend fun cancel(jobId: String) {
        val j = db.jobs().get(jobId) ?: return
        wm.cancelUniqueWork("job-$jobId")
        db.jobs().setStage(jobId, JobStage.CANCELED.name, j.progress)
        db.jobs().setFinished(jobId, System.currentTimeMillis())
        j.serverJobId?.let { runCatching { backend.cancelJob(it) } }
    }

    /** إعادة المحاولة بعد فشل: تُحفظ النتائج السابقة (الكلمات المضافة، المرشّحون، ملفات العمل) ولا تُعاد من الصفر. */
    suspend fun retry(jobId: String) {
        val j = db.jobs().get(jobId) ?: return
        db.jobs().setPaused(jobId, false)
        val stage = j.resumeStage ?: JobStage.QUEUED.name
        db.jobs().setStage(jobId, stage, j.progress)
        db.jobs().setLastError(jobId, null)
        val extract = stage == JobStage.EXTRACTING_WORDS.name || stage == JobStage.UPLOADING.name && db.candidates().count(jobId) > 0
        enqueue(jobId, if (extract) "EXTRACT" else "PROCESS", j.cloud)
    }

    /** اعتماد المتحدثين: يبدأ استخراج الكلمات. */
    suspend fun approveSpeakers(jobId: String) {
        val j = db.jobs().get(jobId) ?: return
        db.jobs().setStage(jobId, JobStage.EXTRACTING_WORDS.name, 0.8f)
        enqueue(jobId, "EXTRACT", j.cloud)
    }

    suspend fun deleteFinished(jobId: String) {
        val j = db.jobs().get(jobId) ?: return
        db.candidates().deleteForJob(jobId)
        db.speakers().deleteForJob(jobId)
        db.jobs().delete(jobId)
        j.serverJobId?.let { runCatching { backend.deleteJob(it) } }
    }

    fun enqueueSync() {
        if (!settings.current.syncToCloud) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
        wm.enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, req)
    }
}
