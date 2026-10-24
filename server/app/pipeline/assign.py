"""إسناد الكلمات إلى المتحدثين من أدوار الكلام (دالة نقية قابلة للاختبار)."""
from typing import Dict, List, Optional, Tuple

Turn = Tuple[float, float, str]  # start_s, end_s, label


def _overlap(a0: float, a1: float, b0: float, b1: float) -> float:
    return max(0.0, min(a1, b1) - max(a0, b0))


def assign_speakers(words: List[dict], turns: List[Turn]) -> List[dict]:
    """يضيف speaker (أو None إن لم يغطِّ أي دور الكلمة) و overlap (نسبة تزامن متحدث آخر)."""
    out = []
    for w in words:
        s, e = w["start_ms"] / 1000.0, w["end_ms"] / 1000.0
        dur = max(e - s, 1e-6)
        cover: Dict[str, float] = {}
        for t0, t1, lab in turns:
            if t0 >= e:
                continue
            ov = _overlap(s, e, t0, t1)
            if ov > 0:
                cover[lab] = cover.get(lab, 0.0) + ov
        best: Optional[str] = max(cover, key=cover.get) if cover else None
        other = max((v for k, v in cover.items() if k != best), default=0.0)
        out.append({**w, "speaker": best if best and cover[best] / dur >= 0.5 else None, "overlap": min(1.0, other / dur)})
    return out
