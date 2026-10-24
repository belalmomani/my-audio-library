package com.mylibrary.app.work

import com.mylibrary.core.SpeakerTurn
import com.mylibrary.core.TimedWord
import com.mylibrary.core.WavFile

/**
 * واجهات التكامل مع نماذج محلية على الجهاز. لا يُضمَّن أي نموذج افتراضيًا (الحجم والترخيص والدقة العربية تحتاج قرارًا منك).
 * لتفعيل المعالجة المحلية الكاملة: نفّذ الواجهتين (مثلًا بـ whisper.cpp / sherpa-onnx بعد التحقق من الترخيص)
 * وسجّلهما في ServiceLocator. انظر docs/local-models.md.
 */
interface LocalSpeechModel {
    suspend fun transcribe(wav: WavFile, startMs: Long, endMs: Long, language: String): List<TimedWord>
}

interface LocalDiarizer {
    suspend fun diarize(wav: WavFile): List<SpeakerTurn>
}
