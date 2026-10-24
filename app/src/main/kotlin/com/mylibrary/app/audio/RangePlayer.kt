package com.mylibrary.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.mylibrary.core.WavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** تشغيل مدى زمني من ملف WAV (كلمة كاملة أو جزء من تسجيل) عبر AudioTrack. يُوقَف بإلغاء الـ coroutine. */
object RangePlayer {
    suspend fun play(file: File, startMs: Long = 0, endMs: Long = Long.MAX_VALUE) = withContext(Dispatchers.IO) {
        val wav = WavFile.open(file)
        val end = minOf(endMs, wav.durationMs)
        val minBuf = AudioTrack.getMinBufferSize(wav.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(wav.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 16 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        var completed = false
        try {
            track.play()
            var frame = wav.msToFrame(startMs)
            val last = wav.msToFrame(end)
            val step = wav.sampleRate / 4
            var written = 0L
            while (frame < last) {
                coroutineContext.ensureActive()
                val n = minOf(step.toLong(), last - frame).toInt()
                val data = wav.readMono(frame, n)
                if (data.isEmpty()) break
                track.write(data, 0, data.size)
                frame += data.size; written += data.size
            }
            var waited = 0
            while (track.playbackHeadPosition < written && waited < 3000) { coroutineContext.ensureActive(); delay(20); waited += 20 }
            completed = true
        } finally {
            runCatching { if (!completed) { track.pause(); track.flush() } else track.stop() }
            track.release()
        }
    }
}
