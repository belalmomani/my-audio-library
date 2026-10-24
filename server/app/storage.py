"""Cloudflare R2 عبر واجهة S3. كل مستخدم محصور في البادئة users/<id>/."""
import re
from typing import Iterable

import boto3
from botocore.config import Config
from fastapi import HTTPException

from .config import Settings

_KEY_RE = re.compile(r"^[A-Za-z0-9._\-/]{1,300}$")


def user_prefix(user_id: str) -> str:
    return f"users/{user_id}/"


def check_key(user_id: str, key: str) -> str:
    """يرفض أي مفتاح خارج مجلد المستخدم أو يحوي مسارات مشبوهة."""
    if not _KEY_RE.match(key) or ".." in key.split("/") or "//" in key or not key.startswith(user_prefix(user_id)):
        raise HTTPException(403, "مفتاح الملف غير مسموح")
    return key


class R2:
    def __init__(self, s: Settings):
        self.s = s
        self.client = boto3.client(
            "s3",
            endpoint_url=f"https://{s.r2_account_id}.r2.cloudflarestorage.com",
            aws_access_key_id=s.r2_key_id,
            aws_secret_access_key=s.r2_secret,
            region_name="auto",
            config=Config(signature_version="s3v4"),
        )

    def presign_put(self, key: str, content_type: str, size: int) -> str:
        return self.client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.s.r2_bucket, "Key": key, "ContentType": content_type, "ContentLength": size},
            ExpiresIn=self.s.presign_ttl,
        )

    def presign_get(self, key: str) -> str:
        return self.client.generate_presigned_url(
            "get_object", Params={"Bucket": self.s.r2_bucket, "Key": key}, ExpiresIn=self.s.presign_ttl
        )

    def head(self, key: str):
        try:
            return self.client.head_object(Bucket=self.s.r2_bucket, Key=key)
        except Exception:
            return None

    def download(self, key: str, path: str) -> None:
        self.client.download_file(self.s.r2_bucket, key, path)

    def upload(self, path: str, key: str, content_type: str) -> None:
        self.client.upload_file(path, self.s.r2_bucket, key, ExtraArgs={"ContentType": content_type})

    def delete_keys(self, keys: Iterable[str]) -> int:
        keys = list(keys)
        n = 0
        for i in range(0, len(keys), 1000):
            batch = keys[i : i + 1000]
            self.client.delete_objects(
                Bucket=self.s.r2_bucket, Delete={"Objects": [{"Key": k} for k in batch], "Quiet": True}
            )
            n += len(batch)
        return n

    def delete_prefix(self, prefix: str) -> int:
        n = 0
        token = None
        while True:
            kw = {"Bucket": self.s.r2_bucket, "Prefix": prefix}
            if token:
                kw["ContinuationToken"] = token
            r = self.client.list_objects_v2(**kw)
            keys = [o["Key"] for o in r.get("Contents", [])]
            if keys:
                n += self.delete_keys(keys)
            if not r.get("IsTruncated"):
                return n
            token = r["NextContinuationToken"]
