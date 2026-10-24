import json
import subprocess
from pathlib import Path


def run(cmd: list, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)


def probe_duration(path: str) -> float:
    r = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", path])
    return float(json.loads(r.stdout)["format"]["duration"])


def to_wav16k(src: str, dst: str) -> None:
    run(["ffmpeg", "-y", "-i", src, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", dst])


def to_native_mono_wav(src: str, dst: str, max_rate: int = 44100) -> None:
    """يحافظ على معدل العينة الأصلي (حتى 44.1kHz) بقناة واحدة، لاستخدامه في قص الكلمات."""
    run(["ffmpeg", "-y", "-i", src, "-vn", "-ac", "1", "-ar", str(max_rate), "-c:a", "pcm_s16le", dst])


def ensure_dir(p: str) -> Path:
    d = Path(p)
    d.mkdir(parents=True, exist_ok=True)
    return d
