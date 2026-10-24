package com.mylibrary.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** استبدال ملف دون فقدان القديم إن فشلت الكتابة أو التحقق. */
object AtomicFileReplace {
    fun replace(target: File, write: (File) -> Unit) {
        val dir = target.absoluteFile.parentFile!!.also { it.mkdirs() }
        val tmp = File(dir, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            write(tmp)
            require(tmp.exists() && tmp.length() > 0) { "الملف البديل فارغ" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (!target.exists()) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }
        val bak = File(dir, "${target.name}.bak")
        Files.move(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            Files.move(bak.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            tmp.delete()
            throw t
        }
        bak.delete()
    }
}

object FileNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun sanitize(name: String, maxLen: Int = 80): String {
        val s = ILLEGAL.replace(name.trim(), "_").trim('.', ' ')
        return (if (s.isEmpty()) "_" else s).take(maxLen)
    }

    fun unique(base: String, taken: MutableSet<String>): String {
        var n = base
        var i = 2
        while (!taken.add(n.lowercase())) n = "${base}_${i++}"
        return n
    }
}

/** كاتب JSON صغير بلا اعتماديات (يكفي لملف البيانات الوصفية). */
object MiniJson {
    fun write(v: Any?, indent: String = "  ", level: Int = 0): String = buildString { emit(v, indent, level) }

    private fun StringBuilder.emit(v: Any?, ind: String, lvl: Int) {
        when (v) {
            null -> append("null")
            is Boolean, is Int, is Long -> append(v.toString())
            is Double -> append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Number -> append(v.toString())
            is String -> quote(v)
            is Map<*, *> -> {
                if (v.isEmpty()) { append("{}"); return }
                append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) append(",\n")
                    first = false
                    append(ind.repeat(lvl + 1)); quote(k.toString()); append(": "); emit(x, ind, lvl + 1)
                }
                append("\n").append(ind.repeat(lvl)).append("}")
            }
            is Iterable<*> -> {
                val l = v.toList()
                if (l.isEmpty()) { append("[]"); return }
                append("[\n")
                l.forEachIndexed { i, x ->
                    if (i > 0) append(",\n")
                    append(ind.repeat(lvl + 1)); emit(x, ind, lvl + 1)
                }
                append("\n").append(ind.repeat(lvl)).append("]")
            }
            else -> quote(v.toString())
        }
    }

    private fun StringBuilder.quote(s: String) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}

data class ExportWord(
    val id: String,
    val text: String,
    val normalized: String,
    val file: File,
    val sourceName: String?,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val sha256: String?,
    val reviewStatus: String,
    val quality: Double,
)

data class ExportLibrary(val id: String, val name: String, val words: List<ExportWord>)

/**
 * يُنتج ZIP منظّمًا: مجلد لكل متحدث + ملف لكل كلمة + metadata.json + README.txt.
 * لا يدمج الكلمات في ملف صوتي واحد. الملفات المُمرَّرة تكون بالصيغة المطلوبة سلفًا ([extension]).
 */
object ZipExporter {
    fun export(
        libraries: List<ExportLibrary>,
        out: OutputStream,
        extension: String = "wav",
        includeMetadata: Boolean = true,
    ): Int {
        var count = 0
        val folders = mutableSetOf<String>()
        val meta = mutableListOf<Map<String, Any?>>()
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            for (lib in libraries) {
                val folder = FileNames.unique(FileNames.sanitize(lib.name), folders)
                val names = mutableSetOf<String>()
                for (w in lib.words) {
                    if (!w.file.exists()) continue
                    val fname = FileNames.unique(FileNames.sanitize(WordNormalizer.displayForm(w.text).ifEmpty { w.normalized }), names)
                    val path = "$folder/$fname.$extension"
                    zip.putNextEntry(ZipEntry(path))
                    w.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                    meta += mapOf(
                        "word" to w.text, "normalized" to w.normalized, "file" to path,
                        "library_id" to lib.id, "library_name" to lib.name, "word_id" to w.id,
                        "source" to w.sourceName, "start_ms" to w.startMs, "end_ms" to w.endMs,
                        "duration_ms" to w.durationMs, "sha256" to w.sha256,
                        "review_status" to w.reviewStatus, "quality" to w.quality,
                    )
                }
            }
            if (includeMetadata) {
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(MiniJson.write(mapOf("format" to extension, "normalizer_version" to WordNormalizer.VERSION, "words" to meta)).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return count
    }

    private const val README = """مكتبتي الصوتية — بنية المكتبة المُصدَّرة
==========================================
لكل متحدث مجلد مستقل، وفي كل مجلد ملف صوتي واحد لكل كلمة فريدة بصوت المتحدث الأصلي (لا صوت مولَّد).
metadata.json يحتوي لكل كلمة: النص، الملف، معرّف المكتبة/المتحدث، التسجيل المصدر، التوقيتات، الجودة، وبصمة SHA-256.
الكلمة نفسها قد تظهر في مجلدي متحدثين مختلفين، لأن كل مجلد يمثل صوت شخص مختلف.
"""
}
