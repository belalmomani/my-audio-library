import json
import time

import jwt
import pytest
from fastapi.testclient import TestClient

from app import main
from app.config import Settings, get_settings
from app.jobs import JobManager, JobStore, build_result
from app.normalize import normalize
from app.pipeline.assign import assign_speakers
from app.runner import make_runner

SECRET = "test-secret-test-secret-test-secret-123"
UID_A, UID_B = "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"


def tok(uid):
    return jwt.encode({"sub": uid, "aud": "authenticated", "exp": int(time.time()) + 600}, SECRET, algorithm="HS256")


class FakeR2:
    def __init__(self):
        self.objs, self.deleted = {}, []

    def presign_put(self, key, ct, size): return f"https://r2.example/put/{key}?size={size}"
    def presign_get(self, key): return f"https://r2.example/get/{key}"
    def head(self, key): return {"ok": True} if key in self.objs else None
    def delete_keys(self, keys): self.deleted += list(keys); return len(list(keys))
    def delete_prefix(self, p): return 0


@pytest.fixture()
def client(tmp_path):
    s = Settings(jwt_secret=SECRET, db_path=str(tmp_path / "j.sqlite"), work_dir=str(tmp_path / "w"), max_upload_mb=1)
    main.app.dependency_overrides[get_settings] = lambda: s
    r2 = FakeR2()
    main.app.dependency_overrides[main.get_r2] = lambda: r2
    ran = []
    mgr = JobManager(s, JobStore(s.db_path), lambda m, j: ran.append(j["id"]))
    main.app.dependency_overrides[main.get_jobs] = lambda: mgr
    c = TestClient(main.app)
    c.r2, c.ran, c.mgr = r2, ran, mgr
    yield c
    main.app.dependency_overrides.clear()


def H(uid): return {"Authorization": f"Bearer {tok(uid)}"}


def test_normalize_matches_rules():
    assert normalize("أحمد") == normalize("أحمد،") == normalize("«أحمد!»") == normalize("أَحْمَد")
    assert normalize("ذهب") != normalize("يذهب")
    assert normalize("الكتاب") != normalize("كتاب")


def test_auth_required(client):
    assert client.post("/v1/storage/presign", json={"key": "x", "method": "get"}).status_code == 401
    bad = jwt.encode({"sub": UID_A, "aud": "authenticated"}, "wrong-secret-wrong-secret-wrong-secret", algorithm="HS256")
    assert client.post("/v1/storage/presign", json={"key": "x", "method": "get"},
                       headers={"Authorization": f"Bearer {bad}"}).status_code == 401


def test_case7_user_cannot_touch_other_users_objects(client):
    r = client.post("/v1/storage/presign", json={"key": f"users/{UID_B}/words/a.wav", "method": "get"}, headers=H(UID_A))
    assert r.status_code == 403
    r = client.post("/v1/storage/presign", json={"key": f"users/{UID_A}/../{UID_B}/a.wav", "method": "get"}, headers=H(UID_A))
    assert r.status_code == 403
    ok = client.post("/v1/storage/presign", json={"key": f"users/{UID_A}/words/a.wav", "method": "get"}, headers=H(UID_A))
    assert ok.status_code == 200


def test_upload_size_limit(client):
    k = f"users/{UID_A}/tmp/x.mp3"
    assert client.post("/v1/storage/presign", json={"key": k, "method": "put", "size": 5 * 1024 * 1024}, headers=H(UID_A)).status_code == 413
    assert client.post("/v1/storage/presign", json={"key": k, "method": "put", "size": 0}, headers=H(UID_A)).status_code == 400
    assert client.post("/v1/storage/presign", json={"key": k, "method": "put", "size": 1000}, headers=H(UID_A)).status_code == 200


def test_job_requires_consent_and_existing_source(client):
    k = f"users/{UID_A}/tmp/x.mp3"
    body = {"source_key": k, "consent": False}
    assert client.post("/v1/jobs", json=body, headers=H(UID_A)).status_code == 400
    body["consent"] = True
    assert client.post("/v1/jobs", json=body, headers=H(UID_A)).status_code == 404
    client.r2.objs[k] = b"x"
    r = client.post("/v1/jobs", json=body, headers=H(UID_A))
    assert r.status_code == 202
    jid = r.json()["id"]
    assert client.get(f"/v1/jobs/{jid}", headers=H(UID_A)).status_code == 200
    assert client.get(f"/v1/jobs/{jid}", headers=H(UID_B)).status_code == 404  # مهمة مستخدم آخر


def test_job_cancel_and_resume_rules(client):
    jid = client.mgr.store.create(UID_A, f"users/{UID_A}/tmp/x", {})
    assert client.post(f"/v1/jobs/{jid}/resume", headers=H(UID_A)).status_code == 409
    client.mgr.store.update(jid, stage="FAILED")
    assert client.post(f"/v1/jobs/{jid}/resume", headers=H(UID_A)).status_code == 202


def test_assign_speakers_and_overlap():
    words = [
        {"text": "أحمد", "start_ms": 0, "end_ms": 500, "confidence": .9},
        {"text": "تعال", "start_ms": 1000, "end_ms": 1500, "confidence": .9},
        {"text": "هناك", "start_ms": 5000, "end_ms": 5400, "confidence": .9},
    ]
    turns = [(0.0, 0.9, "S0"), (0.8, 2.0, "S1"), (1.0, 1.4, "S0")]
    out = assign_speakers(words, turns)
    assert out[0]["speaker"] == "S0"
    assert out[1]["speaker"] == "S1" and out[1]["overlap"] > 0.5
    assert out[2]["speaker"] is None


def test_build_result_shapes():
    res = build_result([{"text": "أحمد،", "start_ms": 0, "end_ms": 400, "confidence": .9, "speaker": "S0", "overlap": 0}],
                       [(0.0, 1.0, "S0")], {"S0": [0.1, 0.2]}, None)
    assert res["words"][0]["normalized"] == normalize("أحمد")
    assert res["speakers"][0]["embedding"] == [0.1, 0.2]


def test_runner_end_to_end_with_stubbed_models(tmp_path, monkeypatch):
    """يمرّ على خط المعالجة كاملًا بدوال مزيفة بدل ffmpeg والنماذج (لا يختبر دقة التعرف الحقيقية)."""
    from app import runner as rn
    s = Settings(db_path=str(tmp_path / "j.sqlite"), work_dir=str(tmp_path / "w"), enable_asr=True, enable_diarization=True)
    r2 = FakeR2()
    key = f"users/{UID_A}/tmp/x.mp3"
    r2.download = lambda k, p: open(p, "wb").write(b"x")
    monkeypatch.setattr(rn.audio, "probe_duration", lambda p: 10.0)
    monkeypatch.setattr(rn.audio, "to_native_mono_wav", lambda a, b: open(b, "wb").write(b"x"))
    monkeypatch.setattr(rn.audio, "to_wav16k", lambda a, b: open(b, "wb").write(b"x"))
    monkeypatch.setattr(rn.models, "diarize", lambda s, p: ([(0.0, 5.0, "S0"), (5.0, 10.0, "S1")], {}))
    monkeypatch.setattr(rn.models, "transcribe", lambda *a, **k: [
        {"text": "أحمد", "start_ms": 100, "end_ms": 500, "confidence": .9},
        {"text": "أحمد", "start_ms": 6000, "end_ms": 6400, "confidence": .8}])
    store = JobStore(s.db_path)
    mgr = JobManager(s, store, rn.make_runner(s, lambda _s: r2))
    jid = store.create(UID_A, key, {"diarize": True})
    mgr._run(jid)
    job = store.get(jid)
    assert job["stage"] == "COMPLETED", job
    speakers = {w["speaker"] for w in job["result"]["words"]}
    assert speakers == {"S0", "S1"}
    assert key in r2.deleted  # حُذف المصدر المؤقت
    assert not (tmp_path / "w" / jid).exists()  # حُذفت الملفات المؤقتة
