import re
import threading
import time
from typing import Literal, Optional

import httpx
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from .auth import User, current_user
from .config import Settings, get_settings
from .jobs import JobManager, JobStore, TERMINAL
from .runner import make_runner
from .storage import R2, check_key, user_prefix

app = FastAPI(title="مكتبتي الصوتية — خادم المعالجة", version="1.0.0")

_state: dict = {}


def get_r2(s: Settings = Depends(get_settings)) -> R2:
    if "r2" not in _state:
        _state["r2"] = R2(s)
    return _state["r2"]


def get_jobs(s: Settings = Depends(get_settings)) -> JobManager:
    if "jobs" not in _state:
        mgr = JobManager(s, JobStore(s.db_path), make_runner(s))
        _state["jobs"] = mgr
        threading.Thread(target=_sweeper, args=(mgr,), daemon=True).start()
    return _state["jobs"]


def _sweeper(mgr: JobManager):
    while True:
        time.sleep(3600)
        try:
            mgr.sweep()
        except Exception:
            pass


@app.get("/health")
def health(s: Settings = Depends(get_settings)):
    return {"ok": True, "asr": s.enable_asr, "diarization": s.enable_diarization, "separation": s.enable_separation,
            "max_upload_mb": s.max_upload_mb, "max_audio_minutes": s.max_audio_minutes}


class PresignIn(BaseModel):
    key: str
    method: Literal["put", "get"]
    content_type: str = "application/octet-stream"
    size: int = Field(default=0, ge=0)


@app.post("/v1/storage/presign")
def presign(body: PresignIn, user: User = Depends(current_user), r2: R2 = Depends(get_r2),
            s: Settings = Depends(get_settings)):
    key = check_key(user.id, body.key)
    if body.method == "get":
        return {"url": r2.presign_get(key), "expires_in": s.presign_ttl}
    if body.size <= 0:
        raise HTTPException(400, "حجم الملف مطلوب للرفع")
    if body.size > s.max_upload_mb * 1024 * 1024:
        raise HTTPException(413, f"الحد الأقصى للرفع {s.max_upload_mb} ميغابايت")
    return {"url": r2.presign_put(key, body.content_type, body.size), "expires_in": s.presign_ttl,
            "headers": {"Content-Type": body.content_type}}


class JobOptions(BaseModel):
    separate: bool = False
    diarize: bool = True
    language: str = "ar"
    keep_source: bool = False


class JobIn(BaseModel):
    source_key: str
    options: JobOptions = JobOptions()
    consent: bool = Field(description="موافقة المستخدم الصريحة على معالجة تسجيله على الخادم")


@app.post("/v1/jobs", status_code=202)
def create_job(body: JobIn, user: User = Depends(current_user), r2: R2 = Depends(get_r2),
               mgr: JobManager = Depends(get_jobs)):
    if not body.consent:
        raise HTTPException(400, "لا تُعالَج التسجيلات على الخادم دون موافقة صريحة")
    key = check_key(user.id, body.source_key)
    if not r2.head(key):
        raise HTTPException(404, "لم يُرفع الملف المصدر بعد")
    jid = mgr.store.create(user.id, key, body.options.model_dump())
    mgr.submit(jid)
    return {"id": jid}


def _public(job: dict) -> dict:
    return {k: job[k] for k in ("id", "stage", "progress", "error", "warnings", "result")}


@app.get("/v1/jobs/{jid}")
def get_job(jid: str, user: User = Depends(current_user), mgr: JobManager = Depends(get_jobs)):
    job = mgr.store.get(jid, user.id)
    if not job:
        raise HTTPException(404, "المهمة غير موجودة")
    return _public(job)


@app.post("/v1/jobs/{jid}/cancel")
def cancel_job(jid: str, user: User = Depends(current_user), mgr: JobManager = Depends(get_jobs)):
    if not mgr.store.get(jid, user.id):
        raise HTTPException(404, "المهمة غير موجودة")
    mgr.cancel(jid)
    return {"ok": True}


@app.post("/v1/jobs/{jid}/resume", status_code=202)
def resume_job(jid: str, user: User = Depends(current_user), mgr: JobManager = Depends(get_jobs)):
    """يعيد تشغيل مهمة فاشلة/ملغاة؛ تُستأنف من نقاط الحفظ الموجودة في مجلد العمل (ما لم يُمسح)."""
    job = mgr.store.get(jid, user.id)
    if not job:
        raise HTTPException(404, "المهمة غير موجودة")
    if job["stage"] not in ("FAILED", "CANCELED"):
        raise HTTPException(409, "المهمة ليست فاشلة أو ملغاة")
    mgr.store.update(jid, stage="QUEUED", error=None)
    mgr.submit(jid)
    return {"id": jid}


@app.delete("/v1/jobs/{jid}")
def delete_job(jid: str, user: User = Depends(current_user), mgr: JobManager = Depends(get_jobs)):
    job = mgr.store.get(jid, user.id)
    if not job:
        raise HTTPException(404, "المهمة غير موجودة")
    if job["stage"] not in TERMINAL:
        mgr.cancel(jid)
    mgr.cleanup(jid)
    mgr.store.delete(jid)
    return {"ok": True}


_UUID = re.compile(r"^[0-9a-fA-F-]{36}$")


@app.delete("/v1/libraries/{library_id}")
def delete_library(library_id: str, user: User = Depends(current_user), r2: R2 = Depends(get_r2),
                   s: Settings = Depends(get_settings)):
    """يحذف ملفات المكتبة من R2 ثم صفوفها. يعمل بتوكن المستخدم نفسه فيمرّ عبر RLS."""
    if not _UUID.match(library_id):
        raise HTTPException(400, "معرّف غير صالح")
    h = {"apikey": s.supabase_anon_key, "Authorization": f"Bearer {user.token}"}
    with httpx.Client(timeout=30) as c:
        r = c.post(f"{s.supabase_url}/rest/v1/rpc/library_object_keys", headers=h, json={"p_library": library_id})
        if r.status_code != 200:
            raise HTTPException(502, "تعذّر قراءة ملفات المكتبة")
        keys = [k for k in r.json() if isinstance(k, str) and k.startswith(user_prefix(user.id))]
        r2.delete_keys(keys)
        d = c.post(f"{s.supabase_url}/rest/v1/rpc/delete_library_rows", headers=h, json={"p_library": library_id})
        if d.status_code not in (200, 204):
            raise HTTPException(502, "حُذفت الملفات لكن فشل حذف الصفوف؛ أعد المحاولة")
    return {"deleted_files": len(keys)}


@app.delete("/v1/words/{word_id}")
def delete_word(word_id: str, user: User = Depends(current_user), r2: R2 = Depends(get_r2),
                s: Settings = Depends(get_settings)):
    """يحذف ملف الكلمة من R2 ثم صفها (بتوكن المستخدم نفسه فيمرّ عبر RLS)."""
    if not _UUID.match(word_id):
        raise HTTPException(400, "معرّف غير صالح")
    h = {"apikey": s.supabase_anon_key, "Authorization": f"Bearer {user.token}"}
    with httpx.Client(timeout=30) as c:
        r = c.get(f"{s.supabase_url}/rest/v1/audio_files", headers=h, params={"word_id": f"eq.{word_id}", "select": "object_key"})
        if r.status_code != 200:
            raise HTTPException(502, "تعذّر قراءة ملف الكلمة")
        keys = [x["object_key"] for x in r.json() if str(x.get("object_key", "")).startswith(user_prefix(user.id))]
        r2.delete_keys(keys)
        d = c.delete(f"{s.supabase_url}/rest/v1/words", headers=h, params={"id": f"eq.{word_id}"})
        if d.status_code not in (200, 204):
            raise HTTPException(502, "حُذف الملف لكن فشل حذف الصف؛ أعد المحاولة")
    return {"deleted_files": len(keys)}


@app.delete("/v1/account")
def delete_account(user: User = Depends(current_user), r2: R2 = Depends(get_r2), s: Settings = Depends(get_settings)):
    """حذف الحساب: كل كائنات المستخدم في R2 ثم المستخدم نفسه (والصفوف تُحذف بالتسلسل)."""
    if not s.supabase_service_key:
        raise HTTPException(501, "SUPABASE_SERVICE_ROLE_KEY غير مضبوط على الخادم")
    n = r2.delete_prefix(user_prefix(user.id))
    with httpx.Client(timeout=30) as c:
        r = c.delete(f"{s.supabase_url}/auth/v1/admin/users/{user.id}",
                     headers={"apikey": s.supabase_service_key, "Authorization": f"Bearer {s.supabase_service_key}"})
    if r.status_code not in (200, 204):
        raise HTTPException(502, "حُذفت الملفات لكن فشل حذف الحساب؛ أعد المحاولة")
    return {"deleted_files": n}
