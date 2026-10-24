package com.mylibrary.app.data

import android.content.Context
import android.net.Uri
import com.mylibrary.app.audio.OggOpusEncoder
import com.mylibrary.core.ExportLibrary
import com.mylibrary.core.ExportWord
import com.mylibrary.core.Hashing
import com.mylibrary.core.ZipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** تصدير المكتبات إلى ZIP منظّم (مجلد لكل متحدث + ملف لكل كلمة + metadata.json + README). لا يدمج الكلمات في ملف واحد. */
class Exporter(private val ctx: Context, private val db: AppDatabase) {
    data class Options(val format: String = "wav", val newOnly: Boolean = false, val metadata: Boolean = true)

    private val prefs = ctx.getSharedPreferences("export", Context.MODE_PRIVATE)

    /** @param libraryIds null = كل المكتبات. يعيد عدد الكلمات المصدَّرة. */
    suspend fun exportLibraries(libraryIds: List<String>?, uri: Uri, o: Options): Int = withContext(Dispatchers.IO) {
        val since = if (o.newOnly) prefs.getLong("last", 0L) else 0L
        val tmp = File(ctx.cacheDir, "export").also { it.deleteRecursively(); it.mkdirs() }
        try {
            val libs = db.libraries().all().filter { libraryIds == null || it.id in libraryIds }
            val export = libs.map { l ->
                val words = db.words().byLibrary(l.id)
                    .filter { it.extractedAt > since && File(it.localPath).exists() }
                    .sortedBy { it.normalized }
                ExportLibrary(l.id, l.name, words.map { toExportWord(it, o.format, tmp) })
            }.filter { it.words.isNotEmpty() }
            if (export.isEmpty()) return@withContext 0
            val out = ctx.contentResolver.openOutputStream(uri) ?: error("تعذّر فتح ملف الوجهة")
            val n = out.use { ZipExporter.export(export, it, o.format, o.metadata) }
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            n
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** تصدير كلمة واحدة كملف صوتي مستقل. */
    suspend fun exportWord(wordId: String, uri: Uri, format: String) = withContext(Dispatchers.IO) {
        val w = db.words().get(wordId) ?: error("الكلمة غير موجودة")
        val tmp = File(ctx.cacheDir, "export").also { it.mkdirs() }
        try {
            val file = toExportWord(w, format, tmp).file
            val out = ctx.contentResolver.openOutputStream(uri) ?: error("تعذّر فتح ملف الوجهة")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun toExportWord(w: WordEntity, format: String, tmp: File): ExportWord {
        val src = File(w.localPath)
        val file = if (format == "ogg" && OggOpusEncoder.isSupported()) {
            File(tmp, "${w.id}.ogg").also { OggOpusEncoder.encode(src, it) }
        } else src
        return ExportWord(
            id = w.id, text = w.text, normalized = w.normalized, file = file, sourceName = w.sourceName,
            startMs = w.startMs, endMs = w.endMs, durationMs = w.durationMs,
            sha256 = if (file === src) w.sha256 else Hashing.sha256(file),
            reviewStatus = w.reviewStatus, quality = w.quality,
        )
    }
}
