import os
from dataclasses import dataclass


def _b(name: str, default: bool) -> bool:
    return os.environ.get(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    supabase_url: str = os.environ.get("SUPABASE_URL", "")
    supabase_anon_key: str = os.environ.get("SUPABASE_ANON_KEY", "")
    supabase_service_key: str = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    jwt_secret: str = os.environ.get("SUPABASE_JWT_SECRET", "")
    jwks_url: str = os.environ.get("SUPABASE_JWKS_URL", "")
    r2_account_id: str = os.environ.get("R2_ACCOUNT_ID", "")
    r2_key_id: str = os.environ.get("R2_ACCESS_KEY_ID", "")
    r2_secret: str = os.environ.get("R2_SECRET_ACCESS_KEY", "")
    r2_bucket: str = os.environ.get("R2_BUCKET", "")
    max_upload_mb: int = int(os.environ.get("MAX_UPLOAD_MB", "1024"))
    max_audio_minutes: int = int(os.environ.get("MAX_AUDIO_MINUTES", "240"))
    presign_ttl: int = int(os.environ.get("PRESIGN_TTL_SECONDS", "900"))
    max_jobs: int = int(os.environ.get("MAX_CONCURRENT_JOBS", "1"))
    work_dir: str = os.environ.get("WORK_DIR", "/data/work")
    db_path: str = os.environ.get("DB_PATH", "/data/jobs.sqlite")
    job_ttl_hours: int = int(os.environ.get("JOB_TTL_HOURS", "24"))
    enable_asr: bool = _b("ENABLE_ASR", True)
    enable_diarization: bool = _b("ENABLE_DIARIZATION", False)
    enable_separation: bool = _b("ENABLE_SEPARATION", False)
    whisper_model: str = os.environ.get("WHISPER_MODEL", "large-v3")
    whisper_device: str = os.environ.get("WHISPER_DEVICE", "auto")
    whisper_compute: str = os.environ.get("WHISPER_COMPUTE_TYPE", "int8")
    hf_token: str = os.environ.get("HF_TOKEN", "")


def get_settings() -> Settings:
    return Settings()
