"""تغليف النماذج الثقيلة. الاستيراد كسول: الخادم يعمل بدونها وتُرجع الواجهة خطأً واضحًا عند طلبها."""
import json
import os
from pathlib import Path
from typing import Callable, List, Optional

from ..config import Settings


class ModelUnavailable(RuntimeError):
    pass


def transcribe(s: Settings, wav16k: str, language: str, checkpoint: Path, on_progress: Callable[[float], None],
               cancelled: Callable[[], bool], duration: float) -> List[dict]:
    """faster-whisper مع word_timestamps. يحفظ نقطة استئناف (JSON lines) ويكمل منها عند إعادة التشغيل."""
    try:
        from faster_whisper import WhisperModel
    except ImportError as e:  # pragma: no cover
        raise ModelUnavailable("faster-whisper غير مثبّت (pip install -r requirements-ml.txt)") from e

    words: List[dict] = []
    resume_from = 0.0
    if checkpoint.exists():
        for line in checkpoint.read_text(encoding="utf-8").splitlines():
            if line.strip():
                words.append(json.loads(line))
        if words:
            resume_from = words[-1]["end_ms"] / 1000.0

    model = WhisperModel(s.whisper_model, device=s.whisper_device, compute_type=s.whisper_compute)
    segments, _info = model.transcribe(
        wav16k,
        language=None if language == "auto" else language,
        word_timestamps=True,
        vad_filter=True,
        condition_on_previous_text=False,  # يقلل الهلوسة والتكرار في التسجيلات الطويلة
        clip_timestamps=[resume_from] if resume_from > 0 else [0],
    )
    with checkpoint.open("a", encoding="utf-8") as ck:
        for seg in segments:
            if cancelled():
                raise InterruptedError("canceled")
            for w in seg.words or []:
                item = {
                    "text": w.word.strip(),
                    "start_ms": int(w.start * 1000),
                    "end_ms": int(w.end * 1000),
                    "confidence": float(w.probability),
                }
                words.append(item)
                ck.write(json.dumps(item, ensure_ascii=False) + "\n")
            ck.flush()
            on_progress(min(1.0, seg.end / max(duration, 1e-6)))
    return words


def diarize(s: Settings, wav16k: str):
    """يعيد (turns, embeddings_by_label). يتطلب pyannote.audio وتوكن Hugging Face بعد قبول شروط النموذج."""
    try:
        from pyannote.audio import Pipeline
    except ImportError as e:  # pragma: no cover
        raise ModelUnavailable("pyannote.audio غير مثبّت") from e
    if not s.hf_token:
        raise ModelUnavailable("HF_TOKEN مطلوب لتحميل نموذج pyannote (اقبل شروط النموذج على Hugging Face)")
    pipe = Pipeline.from_pretrained("pyannote/speaker-diarization-3.1", use_auth_token=s.hf_token)
    try:
        import torch
        if s.whisper_device in ("cuda", "auto") and torch.cuda.is_available():
            pipe.to(torch.device("cuda"))
    except Exception:
        pass
    result = pipe(wav16k, return_embeddings=True)
    diarization, embeddings = result if isinstance(result, tuple) else (result, None)
    turns = [(t.start, t.end, lab) for t, _, lab in diarization.itertracks(yield_label=True)]
    emb = {}
    if embeddings is not None:
        for i, lab in enumerate(diarization.labels()):
            emb[lab] = [float(x) for x in embeddings[i]]
    return turns, emb


def separate_vocals(src: str, out_dir: Path) -> str:
    """demucs --two-stems=vocals. يعيد مسار ملف الكلام المعزول."""
    try:
        import demucs  # noqa: F401
    except ImportError as e:  # pragma: no cover
        raise ModelUnavailable("demucs غير مثبّت") from e
    import subprocess
    subprocess.run(["python", "-m", "demucs", "--two-stems=vocals", "-o", str(out_dir), src], check=True,
                   capture_output=True)
    found = list(out_dir.rglob("vocals.wav"))
    if not found:
        raise RuntimeError("لم ينتج demucs ملف vocals.wav")
    return str(found[0])
