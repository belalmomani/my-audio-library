"""التحقق من توكن Supabase. لا يُقبل أي طلب بلا توكن صالح، والمعرّف يؤخذ من التوكن فقط."""
from dataclasses import dataclass

import jwt
from fastapi import Depends, Header, HTTPException

from .config import Settings, get_settings

_jwks_client = None


@dataclass
class User:
    id: str
    token: str


def verify_token(token: str, s: Settings) -> str:
    try:
        if s.jwks_url:
            global _jwks_client
            if _jwks_client is None:
                _jwks_client = jwt.PyJWKClient(s.jwks_url)
            key = _jwks_client.get_signing_key_from_jwt(token).key
            claims = jwt.decode(token, key, algorithms=["ES256", "RS256"], audience="authenticated")
        elif s.jwt_secret:
            claims = jwt.decode(token, s.jwt_secret, algorithms=["HS256"], audience="authenticated")
        else:
            raise HTTPException(500, "الخادم غير مهيأ للتحقق من التوكن")
    except jwt.PyJWTError as e:
        raise HTTPException(401, f"توكن غير صالح: {e.__class__.__name__}")
    sub = claims.get("sub")
    if not sub:
        raise HTTPException(401, "التوكن بلا معرّف مستخدم")
    return sub


def current_user(authorization: str = Header(default=""), s: Settings = Depends(get_settings)) -> User:
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(401, "مطلوب تسجيل الدخول")
    token = authorization[7:].strip()
    return User(verify_token(token, s), token)
