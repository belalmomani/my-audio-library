"""مهام المعالجة: SQLite + خيوط خلفية. تدعم الإلغاء والاستئناف من نقاط الحفظ ومسح الملفات المؤقتة."""
import json
import shutil
import sqlite3
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Optional

from .config import Settings
from .normalize import normalize

STAGES = ("QUEUED", "EXTRACTING_AUDIO", "SEPARATING", "DIARIZING", "TRANSCRIBING", "COMPLETED",
          "COMPLETED_WITH_WARNINGS", "FAILED", "CANCELED")
TERMINAL = {"COMPLETED", "COMPLETED_WITH_WARNINGS", "FAILED", "CANCELED"}


class JobStore:
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute(
            "create table if not exists jobs(id text primary key, user_id text, stage text, progress real, "
            "options text, source_key text, result text, error text, warnings text, created real, updated real)"
        )
        self.db.commit()

    def create(self, user_id: str, source_key: str, options: dict) -> str:
        jid = uuid.uuid4().hex
        now = time.time()
        with self._lock:
            self.db.execute("insert into jobs values(?,?,?,?,?,?,?,?,?,?,?)",
                            (jid, user_id, "QUEUED", 0.0, json.dumps(options), source_key, None, None, "[]", now, now))
            self.db.commit()
        return jid

    def update(self, jid: str, **kw):
        kw["updated"] = time.time()
        cols = ",".join(f"{k}=?" for k in kw)
        with self._lock:
            self.db.execute(f"update jobs set {cols} where id=?", (*kw.values(), jid))
            self.db.commit()

    def get(self, jid: str, user_id: Optional[str] = None) -> Optional[dict]:
        with self._lock:
            r = self.db.execute("select id,user_id,stage,progress,options,source_key,result,error,warnings,created,updated "
                                "from jobs where id=?", (jid,)).fetchone()
        if not r or (user_id and r[1] != user_id):
            return None
        return {"id": r[0], "user_id": r[1], "stage": r[2], "progress": r[3], "options": json.loads(r[4]),
                "source_key": r[5], "result": json.loads(r[6]) if r[6] else None, "error": r[7],
                "warnings": json.loads(r[8] or "[]"), "created": r[9], "updated": r[10]}

    def expired(self, ttl_hours: int):
        cutoff = time.time() - ttl_hours * 3600
        with self._lock:
            return [x[0] for x in self.db.execute("select id from jobs where updated<? and stage in "
                                                  "('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELED')", (cutoff,))]

    def delete(self, jid: str):
        with self._lock:
            self.db.execute("delete from jobs where id=?", (jid,))
            self.db.commit()


class JobManager:
    def __init__(self, s: Settings, store: JobStore, runner: Callable[["JobManager", dict], None]):
        self.s, self.store, self.runner = s, store, runner
        self.pool = ThreadPoolExecutor(max_workers=max(1, s.max_jobs))
        self._cancel: set = set()

    def work_dir(self, jid: str) -> Path:
        d = Path(self.s.work_dir) / jid
        d.mkdir(parents=True, exist_ok=True)
        return d

    def submit(self, jid: str):
        self._cancel.discard(jid)
        self.pool.submit(self._run, jid)

    def cancel(self, jid: str):
        self._cancel.add(jid)

    def is_cancelled(self, jid: str) -> bool:
        return jid in self._cancel

    def _run(self, jid: str):
        job = self.store.get(jid)
        if not job or job["stage"] in TERMINAL and job["stage"] != "FAILED":
            return
        try:
            self.runner(self, job)
        except InterruptedError:
            self.store.update(jid, stage="CANCELED")
        except Exception as e:  # noqa: BLE001
            self.store.update(jid, stage="FAILED", error=f"{e.__class__.__name__}: {e}")

    def cleanup(self, jid: str):
        shutil.rmtree(Path(self.s.work_dir) / jid, ignore_errors=True)

    def sweep(self):
        for jid in self.store.expired(self.s.job_ttl_hours):
            self.cleanup(jid)
            self.store.delete(jid)


def build_result(words: list, turns: list, embeddings: dict, processed_key: Optional[str]) -> dict:
    """يجمّع الكلمات بحسب المتحدث؛ كلمات بلا متحدث تبقى speaker=None لتذهب إلى المراجعة في التطبيق."""
    labels = sorted({t[2] for t in turns})
    names = {lab: i + 1 for i, lab in enumerate(labels)}
    speakers = []
    for lab in labels:
        segs = [{"start_ms": int(a * 1000), "end_ms": int(b * 1000)} for a, b, l in turns if l == lab]
        speakers.append({"label": lab, "index": names[lab], "segments": segs, "embedding": embeddings.get(lab)})
    for w in words:
        w["normalized"] = normalize(w["text"])
    return {"speakers": speakers, "words": words, "processed_key": processed_key}
