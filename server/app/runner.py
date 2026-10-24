"""خط المعالجة الكامل لمهمة واحدة. كل مرحلة تُحفظ نتيجتها في مجلد العمل فتُتخطى عند الاستئناف."""
import json
from pathlib import Path

from .config import Settings
from .jobs import JobManager, build_result
from .pipeline import audio, models
from .pipeline.assign import assign_speakers
from .storage import R2


def make_runner(s: Settings, r2_factory=lambda s: R2(s)):
    def run(mgr: JobManager, job: dict) -> None:
        jid, uid, opts = job["id"], job["user_id"], job["options"]
        store = mgr.store
        wd = mgr.work_dir(jid)
        r2 = r2_factory(s)
        warnings: list = []

        def stage(name: str, p: float = 0.0):
            if mgr.is_cancelled(jid):
                raise InterruptedError("canceled")
            store.update(jid, stage=name, progress=p)

        # 1) تنزيل وفك الترميز
        stage("EXTRACTING_AUDIO", 0.0)
        src = wd / "source.bin"
        if not src.exists():
            r2.download(job["source_key"], str(src))
        dur = audio.probe_duration(str(src))
        if dur > s.max_audio_minutes * 60:
            raise ValueError(f"التسجيل أطول من الحد المسموح ({s.max_audio_minutes} دقيقة)")
        native = wd / "native.wav"
        if not native.exists():
            audio.to_native_mono_wav(str(src), str(native))
        working = native

        # 2) فصل الكلام عن الموسيقى (اختياري)
        processed_key = None
        if opts.get("separate"):
            stage("SEPARATING", 0.1)
            if not s.enable_separation:
                warnings.append("فصل الموسيقى غير مفعّل على هذا الخادم؛ استُخدم الصوت الأصلي.")
            else:
                vocals = wd / "vocals_native.wav"
                if not vocals.exists():
                    raw = models.separate_vocals(str(native), wd / "demucs")
                    audio.to_native_mono_wav(raw, str(vocals))
                working = vocals
                processed_key = f"users/{uid}/processed/{jid}.wav"
                r2.upload(str(vocals), processed_key, "audio/wav")
                warnings.append("فُصل الكلام بنموذج تقريبي؛ قد تبقى آثار للموسيقى.")

        wav16 = wd / "audio16k.wav"
        if not wav16.exists():
            audio.to_wav16k(str(working), str(wav16))

        # 3) المتحدثون
        turns, emb = [], {}
        if opts.get("diarize", True):
            stage("DIARIZING", 0.2)
            cache = wd / "turns.json"
            if cache.exists():
                d = json.loads(cache.read_text(encoding="utf-8"))
                turns, emb = [tuple(t) for t in d["turns"]], d["emb"]
            elif s.enable_diarization:
                turns, emb = models.diarize(s, str(wav16))
                cache.write_text(json.dumps({"turns": turns, "emb": emb}), encoding="utf-8")
            else:
                warnings.append("تحديد المتحدثين غير مفعّل على هذا الخادم؛ ستُعامل كل الكلمات كمتحدث غير معروف.")

        # 4) التعرف على الكلام
        stage("TRANSCRIBING", 0.3)
        if not s.enable_asr:
            raise models.ModelUnavailable("التعرف على الكلام غير مفعّل على هذا الخادم")
        words = models.transcribe(
            s, str(wav16), opts.get("language", "ar"), wd / "words.jsonl",
            on_progress=lambda p: store.update(jid, progress=0.3 + 0.7 * p),
            cancelled=lambda: mgr.is_cancelled(jid), duration=dur,
        )
        words = assign_speakers(words, turns)
        if not turns:
            warnings.append("لم تُحدَّد هويات المتحدثين؛ راجعها في التطبيق.")
        result = build_result(words, turns, emb, processed_key)

        # 5) تنظيف: تُحذف نسخة المصدر المؤقتة من R2 ما لم يطلب المستخدم الاحتفاظ بها
        if not opts.get("keep_source", False) and job["source_key"].startswith(f"users/{uid}/tmp/"):
            r2.delete_keys([job["source_key"]])
        store.update(jid, result=json.dumps(result, ensure_ascii=False), progress=1.0,
                     warnings=json.dumps(warnings, ensure_ascii=False),
                     stage="COMPLETED_WITH_WARNINGS" if warnings else "COMPLETED")
        mgr.cleanup(jid)

    return run
