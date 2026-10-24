package com.mylibrary.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** قارئ WAV (PCM 16 بت) بالوصول العشوائي؛ لا يحمّل الملف كله في الذاكرة. */
class WavFile private constructor(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    private val dataOffset: Long,
    private val dataBytes: Long,
) {
    val totalFrames: Long get() = dataBytes / (2L * channels)
    val durationMs: Long get() = totalFrames * 1000L / sampleRate

    fun msToFrame(ms: Long): Long = (ms * sampleRate / 1000L).coerceIn(0L, totalFrames)

    /** يقرأ [frameCount] إطارًا ابتداءً من [startFrame] ويحوّلها إلى قناة واحدة. */
    fun readMono(startFrame: Long, frameCount: Int): ShortArray {
        val s = startFrame.coerceIn(0L, totalFrames)
        val n = minOf(frameCount.toLong(), totalFrames - s).toInt().coerceAtLeast(0)
        if (n == 0) return ShortArray(0)
        val bytes = ByteArray(n * 2 * channels)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(dataOffset + s * 2L * channels)
            raf.readFully(bytes)
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (c in 0 until channels) sum += bb.short.toInt()
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    fun readMonoMs(startMs: Long, endMs: Long): ShortArray {
        val a = msToFrame(startMs)
        val b = msToFrame(endMs)
        return readMono(a, (b - a).toInt())
    }

    companion object {
        fun open(file: File): WavFile {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 44) throw IOException("ملف WAV قصير جدًا")
                val head = ByteArray(12).also { raf.readFully(it) }
                if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE")
                    throw IOException("ليس ملف WAV صالحًا")
                var rate = 0
                var ch = 0
                var bits = 0
                var tag = 0
                var pos = 12L
                while (pos + 8 <= len) {
                    raf.seek(pos)
                    val h = ByteArray(8).also { raf.readFully(it) }
                    val id = String(h, 0, 4, Charsets.US_ASCII)
                    val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    val body = pos + 8
                    if (id == "fmt ") {
                        val f = ByteArray(16).also { raf.readFully(it) }
                        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                        tag = bb.short.toInt() and 0xFFFF
                        ch = bb.short.toInt()
                        rate = bb.int
                        bb.int; bb.short
                        bits = bb.short.toInt()
                    } else if (id == "data") {
                        if (bits != 16 || (tag != 1 && tag != 0xFFFE) || ch < 1 || rate < 1)
                            throw IOException("صيغة WAV غير مدعومة (المطلوب PCM 16 بت)")
                        val avail = len - body
                        val dataLen = if (size == 0L || size > avail) avail else size
                        return WavFile(file, rate, ch, body, dataLen)
                    }
                    pos = body + size + (size and 1L)
                }
            }
            throw IOException("لم يُعثر على بيانات الصوت في الملف")
        }
    }
}

object WavWriter {
    fun toBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        bb.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataLen)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(toBytes(samples, sampleRate))
    }

    /** كاتب متدفّق: يُستخدم عند فك ترميز تسجيلات طويلة دون تحميلها كلها في الذاكرة. */
    class Streaming(private val file: File, private val sampleRate: Int) : AutoCloseable {
        private val raf = RandomAccessFile(file.also { it.parentFile?.mkdirs() }, "rw").also {
            it.setLength(0)
            it.write(ByteArray(44))
        }
        private var bytes = 0L

        fun write(samples: ShortArray, count: Int = samples.size) {
            val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bb.putShort(samples[i])
            raf.write(bb.array())
            bytes += count * 2L
        }

        override fun close() {
            val header = toBytes(ShortArray(0), sampleRate)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(4, (36 + bytes).toInt())
            bb.putInt(40, bytes.toInt())
            raf.seek(0)
            raf.write(header, 0, 44)
            raf.close()
        }
    }
}

object Hashing {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

