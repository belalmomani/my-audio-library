package com.mylibrary.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mylibrary.core.WavWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * يفكّ ترميز أي ملف صوتي/فيديو يدعمه الجهاز (MP3, WAV, M4A, AAC, FLAC, MP4, MOV...) إلى WAV أحادي 16 بت
 * بمعدل المصدر، تدفقيًا (لا يحمّل التسجيل في الذاكرة)، فتصلح التسجيلات الطويلة.
 * MOV: يعمل إن كان ترميز الصوت مدعومًا على الجهاز؛ وإلا يُرمى خطأ واضح.
 */
object AudioDecoder {
    data class Info(val durationMs: Long, val sampleRate: Int)

    fun decodeToWav(
        ctx: Context, uri: Uri, out: File,
        isCancelled: () -> Boolean, onProgress: (Float) -> Unit,
    ): Info {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)
        var codec: MediaCodec? = null
        var writer: WavWriter.Streaming? = null
        try {
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalArgumentException("لا يوجد مسار صوتي في هذا الملف")
            extractor.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val totalUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var lastUs = 0L
            var totalFrames = 0L

            fun consume(bb: ByteBuffer) {
                bb.order(ByteOrder.nativeOrder())
                val frames = bb.remaining() / ((if (isFloat) 4 else 2) * channels)
                if (frames <= 0) return
                val mono = ShortArray(frames)
                if (isFloat) {
                    val fb = bb.asFloatBuffer()
                    for (i in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += fb.get()
                        mono[i] = ((s / channels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                } else {
                    val sb = bb.asShortBuffer()
                    for (i in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += sb.get().toInt()
                        mono[i] = (s / channels).toShort()
                    }
                }
                if (writer == null) writer = WavWriter.Streaming(out, sampleRate)
                writer!!.write(mono)
                totalFrames += frames
            }

            if (mime == "audio/raw") {
                // ملفات WAV: البيانات PCM جاهزة بلا ترميز
                val buf = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    if (isCancelled()) throw CancellationException("canceled")
                    buf.clear()
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.limit(n)
                    lastUs = extractor.sampleTime
                    consume(buf)
                    extractor.advance()
                    if (totalUs > 0) onProgress((lastUs.toFloat() / totalUs).coerceIn(0f, 1f))
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (isCancelled()) throw CancellationException("canceled")
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = extractor.readSampleData(ib, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        oi >= 0 -> {
                            val ob = codec.getOutputBuffer(oi)!!
                            if (info.size > 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                consume(ob)
                                lastUs = info.presentationTimeUs
                                if (totalUs > 0) onProgress((lastUs.toFloat() / totalUs).coerceIn(0f, 1f))
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = codec.outputFormat
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
            }
            if (writer == null) throw IllegalStateException("لم يُنتج فك الترميز أي صوت")
            return Info(totalFrames * 1000L / sampleRate, sampleRate)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { writer?.close() }
            extractor.release()
        }
    }
}
