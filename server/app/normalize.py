"""قاعدة التطابق v1 — مطابقة لـ core/WordNormalizer.kt. أي تعديل يجب أن يُنفَّذ في الاثنين."""
import re
import unicodedata

VERSION = 1
_STRIP = re.compile("[ـً-ٰٟۖ-ۭ]")


def normalize(raw: str) -> str:
    s = _STRIP.sub("", unicodedata.normalize("NFKC", raw))
    return "".join(ch for ch in s if ch.isalnum()).lower()
