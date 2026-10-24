package com.mylibrary.app.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.mylibrary.core.Resample
import com.mylibrary.core.WavFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * تصدير OGG (Opus) عبر MediaCodec + MediaMuxer. يتطلب Android 10+ ووجود مرمّز Opus على الجهاز.
 * إن لم يتوفر يُخفى الخيار من واجهة التصدير (انظر [isSupported]).
 */
object OggOpusEncoder {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals("audio/opus", ignoreCase = true) }
        }

    fun encode(wavFile: File, out: File, bitrate: Int = 64_000) {
        val wav = WavFile.open(wavFile)
        val pcm = Resample.linear(wav.readMono(0, wav.totalFrames.toInt()), wav.sampleRate, 48_000)
        val fmt = MediaFormat.createAudioFormat("audio/opus", 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        val codec = MediaCodec.createEncoderByType("audio/opus")
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var muxerStarted = false
        try {
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear(); ib.order(ByteOrder.nativeOrder())
                        val n = minOf(pcm.size - pos, ib.remaining() / 2)
                        val pts = pos * 1_000_000L / 48_000
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            ib.asShortBuffer().put(pcm, pos, n)
                            codec.queueInputBuffer(ii, 0, n * 2, pts, 0); pos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                    }
                    oi >= 0 -> {
                        val ob: ByteBuffer = codec.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
