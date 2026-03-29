"""
JWT-based authentication helpers.

Token payload: { "sub": "<user_id>", "exp": <unix_ts> }
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import random
import secrets
import smtplib
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from typing import Optional

import bcrypt
import httpx
import jwt
from jwt import InvalidTokenError
from sqlalchemy.orm import Session

from app.core.cache import cache_delete, cache_get, cache_set, cache_set_if_absent
from app.models.auth_models import User

logger = logging.getLogger(__name__)


def _get_settings():
    from app.core.config import get_settings
    return get_settings()


def _ensure_secure_secret_in_production(settings) -> None:
    if settings.APP_ENV.lower() != "production":
        return
    if settings.SECRET_KEY.startswith("CHANGE-THIS-IN-PRODUCTION"):
        raise RuntimeError("Insecure SECRET_KEY in production environment")


def _utcnow() -> datetime:
    return datetime.now(tz=timezone.utc)


# ── Password — uses bcrypt directly (passlib 1.7.4 is incompatible w/ bcrypt 5) ─

def hash_password(plain: str) -> str:
    return bcrypt.hashpw(plain.encode(), bcrypt.gensalt()).decode()


def verify_password(plain: str, hashed: str) -> bool:
    return bcrypt.checkpw(plain.encode(), hashed.encode())


# ── JWT ───────────────────────────────────────────────────────────────────────

def create_access_token(user_id: int) -> str:
    s = _get_settings()
    _ensure_secure_secret_in_production(s)
    expire = _utcnow() + timedelta(days=s.JWT_EXPIRE_DAYS)
    payload = {"sub": str(user_id), "exp": expire}
    return jwt.encode(payload, s.SECRET_KEY, algorithm=s.JWT_ALGORITHM)


def decode_token(token: str) -> Optional[dict]:
    s = _get_settings()
    _ensure_secure_secret_in_production(s)
    try:
        return jwt.decode(token, s.SECRET_KEY, algorithms=[s.JWT_ALGORITHM])
    except InvalidTokenError as exc:
        logger.debug("Token decode failed: %s", exc)
        return None


def is_account_locked(user: User) -> bool:
    if not user.locked_until:
        return False
    locked_until = user.locked_until
    if locked_until.tzinfo is None:
        locked_until = locked_until.replace(tzinfo=timezone.utc)
    return locked_until > _utcnow()


def register_login_failure(db: Session, user: User) -> None:
    s = _get_settings()
    user.failed_login_attempts = int(user.failed_login_attempts or 0) + 1
    if user.failed_login_attempts >= s.AUTH_LOGIN_MAX_FAILURES:
        user.locked_until = _utcnow() + timedelta(minutes=s.AUTH_ACCOUNT_LOCK_MINUTES)
        user.failed_login_attempts = 0
    db.commit()
    db.refresh(user)


def clear_login_failures(db: Session, user: User) -> None:
    if (user.failed_login_attempts or 0) == 0 and user.locked_until is None:
        return
    user.failed_login_attempts = 0
    user.locked_until = None
    db.commit()


def _verification_key(token: str) -> str:
    digest = hashlib.sha256(token.encode()).hexdigest()
    return f"auth:verify-email:{digest}"


def create_email_verification_token(user_id: int) -> str:
    s = _get_settings()
    token = secrets.token_urlsafe(32)
    ttl = s.EMAIL_VERIFICATION_TTL_HOURS * 3600
    cache_set(_verification_key(token), {"user_id": user_id}, ttl=ttl)
    return token


def consume_email_verification_token(token: str) -> Optional[int]:
    key = _verification_key(token)
    payload = cache_get(key)
    if not payload:
        return None
    cache_delete(key)
    user_id = payload.get("user_id") if isinstance(payload, dict) else None
    return int(user_id) if user_id else None


def _signup_request_key(request_id: str) -> str:
    return f"auth:signup:req:{request_id}"


def _hash_code(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()


def _mask_email(email: str) -> str:
    local, _, domain = email.partition("@")
    if not local or not domain:
        return email
    if len(local) <= 2:
        masked_local = local[0] + "*"
    else:
        masked_local = local[0] + ("*" * (len(local) - 2)) + local[-1]
    return f"{masked_local}@{domain}"


def create_signup_challenge(
    username: str,
    email: str,
    password_hash: str,
    boj_handle: Optional[str],
) -> tuple[str, str, int, str]:
    """Create a pending signup challenge and return (request_id, code, ttl, masked_email)."""
    s = _get_settings()
    request_id = secrets.token_urlsafe(18)
    code = f"{random.randint(0, 999999):06d}"
    ttl = s.EMAIL_CODE_TTL_MINUTES * 60

    payload = {
        "username": username.lower(),
        "email": email.lower(),
        "password_hash": password_hash,
        "boj_handle": boj_handle,
        "code_hash": _hash_code(code),
        "created_at": _utcnow().isoformat(),
    }
    cache_set(_signup_request_key(request_id), payload, ttl=ttl)
    return request_id, code, ttl, _mask_email(email)


def consume_signup_challenge(request_id: str, code: str) -> Optional[dict]:
    """Validate and consume pending signup challenge."""
    key = _signup_request_key(request_id)
    payload = cache_get(key)
    if not payload or not isinstance(payload, dict):
        return None

    code_hash = payload.get("code_hash")
    if not code_hash:
        return None
    if not hmac.compare_digest(str(code_hash), _hash_code(code)):
        return None

    cache_delete(key)
    return payload


def send_email_verification_code(email: str, username: str, code: str) -> None:
    """Send a verification code email using Brevo SMTP settings."""
    s = _get_settings()

    required = [s.SMTP_HOST, s.SMTP_USERNAME, s.SMTP_PASSWORD, s.SMTP_FROM_EMAIL]
    if not all(required):
        if s.APP_ENV.lower() == "production":
            raise RuntimeError("SMTP configuration is incomplete")
        logger.warning("SMTP configuration missing; skipping email delivery in non-production")
        return

    msg = EmailMessage()
    msg["Subject"] = "[PS Recommender] Email Verification Code"
    msg["From"] = f"{s.SMTP_FROM_NAME} <{s.SMTP_FROM_EMAIL}>"
    msg["To"] = email
    msg.set_content(
        "\n".join(
            [
                f"Hello {username},",
                "",
                "Your verification code is:",
                f"{code}",
                "",
                f"This code expires in {s.EMAIL_CODE_TTL_MINUTES} minutes.",
                "If you did not request this, you can ignore this email.",
            ]
        )
    )

    if s.SMTP_USE_TLS:
        with smtplib.SMTP(s.SMTP_HOST, s.SMTP_PORT, timeout=10) as smtp:
            smtp.ehlo()
            smtp.starttls()
            smtp.ehlo()
            smtp.login(s.SMTP_USERNAME, s.SMTP_PASSWORD)
            smtp.send_message(msg)
    else:
        with smtplib.SMTP_SSL(s.SMTP_HOST, s.SMTP_PORT, timeout=10) as smtp:
            smtp.login(s.SMTP_USERNAME, s.SMTP_PASSWORD)
            smtp.send_message(msg)


async def verify_captcha(captcha_token: Optional[str], remote_ip: str) -> bool:
    """Verify CAPTCHA token if required, otherwise pass through."""
    s = _get_settings()
    captcha_required = s.CAPTCHA_REQUIRED or s.APP_ENV.lower() == "production"
    
    logger.info(f"[verify_captcha] CAPTCHA_REQUIRED={s.CAPTCHA_REQUIRED}, APP_ENV={s.APP_ENV}, calculated_required={captcha_required}, token_present={bool(captcha_token)}")
    
    # If CAPTCHA is not required, always pass
    if not captcha_required:
        logger.info("[verify_captcha] CAPTCHA not required, returning True")
        return True

    # If CAPTCHA is required but no token provided, fail
    if not captcha_token:
        logger.warning("[verify_captcha] CAPTCHA required but token is missing")
        return False

    # If CAPTCHA is required but secret key is missing, log warning and fail
    if not s.CAPTCHA_SECRET_KEY:
        logger.warning("[verify_captcha] CAPTCHA_REQUIRED is enabled but CAPTCHA_SECRET_KEY is missing")
        return False

    # Prevent trivial replay within short window via nonce deduplication
    nonce_key = f"auth:captcha-nonce:{hashlib.sha256(captcha_token.encode()).hexdigest()}"
    if not cache_set_if_absent(nonce_key, {"used": True}, ttl=180):
        logger.warning("[verify_captcha] Nonce replay detected")
        return False

    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                s.CAPTCHA_VERIFY_URL,
                data={
                    "secret": s.CAPTCHA_SECRET_KEY,
                    "response": captcha_token,
                    "remoteip": remote_ip,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            result = bool(data.get("success", False))
            logger.info(f"[verify_captcha] Google API response: success={data.get('success')}, final_result={result}")
            return result
    except Exception as exc:
        logger.warning("CAPTCHA verification failed: %s", exc)
        return False


# ── DB helpers ────────────────────────────────────────────────────────────────

def get_user_by_id(db: Session, user_id: int) -> Optional[User]:
    return db.query(User).filter(User.id == user_id).first()


def get_user_by_username(db: Session, username: str) -> Optional[User]:
    return db.query(User).filter(User.username == username.lower()).first()


def get_user_by_email(db: Session, email: str) -> Optional[User]:
    return db.query(User).filter(User.email == email.lower()).first()


def create_user(
    db: Session,
    username: str,
    email: str,
    password: str,
    boj_handle: Optional[str] = None,
) -> User:
    user = User(
        username=username.lower(),
        email=email.lower(),
        password_hash=hash_password(password),
        boj_handle=boj_handle or None,
        email_verified=False,
        failed_login_attempts=0,
        locked_until=None,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def create_user_from_password_hash(
    db: Session,
    username: str,
    email: str,
    password_hash: str,
    boj_handle: Optional[str] = None,
    *,
    email_verified: bool = False,
) -> User:
    user = User(
        username=username.lower(),
        email=email.lower(),
        password_hash=password_hash,
        boj_handle=boj_handle or None,
        email_verified=email_verified,
        failed_login_attempts=0,
        locked_until=None,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user
