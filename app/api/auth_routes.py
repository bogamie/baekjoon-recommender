"""
Authentication endpoints.

POST /auth/register/start     — request email verification code
POST /auth/register/complete  — verify code and create account
POST /auth/login     — get JWT
GET  /auth/me        — current user info
PATCH /auth/boj-handle — link / change solved.ac handle
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi import Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.cache import cache_incr
from app.db import get_db
from app.core.config import Settings, get_settings
from app.models.auth_models import (
    LoginRequest,
    RegisterCompleteRequest,
    RegisterStartResponse,
    RegisterRequest,
    TokenResponse,
    UpdateHandleRequest,
    User,
    UserResponse,
)
from app.services.auth_service import (
    clear_login_failures,
    create_access_token,
    create_signup_challenge,
    create_user_from_password_hash,
    decode_token,
    get_user_by_email,
    get_user_by_id,
    get_user_by_username,
    hash_password,
    is_account_locked,
    consume_signup_challenge,
    register_login_failure,
    send_email_verification_code,
    verify_captcha,
    verify_password,
)

router = APIRouter(prefix="/auth", tags=["Auth"])

_bearer = HTTPBearer()
_bearer_optional = HTTPBearer(auto_error=False)


# ── Shared dependency ─────────────────────────────────────────────────────────

def _resolve_user(token: str, db: Session) -> User:
    payload = decode_token(token)
    if not payload:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired token")
    user = get_user_by_id(db, int(payload.get("sub", 0)))
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User not found")
    return user


def _captcha_required(settings: Settings) -> bool:
    return settings.CAPTCHA_REQUIRED or settings.APP_ENV.lower() == "production"


def _enforce_rate_limit(
    request: Request,
    settings: Settings,
    *,
    scope: str,
    principal_hint: str,
    max_attempts: int,
) -> None:
    client_ip = request.client.host if request.client else "unknown"
    principal = principal_hint.strip().lower() if principal_hint else ""
    counter_key = f"auth:rl:{scope}:{client_ip}:{principal}" if principal else f"auth:rl:{scope}:{client_ip}"
    count = cache_incr(counter_key, ttl=settings.AUTH_RATE_LIMIT_WINDOW_SECONDS)
    if count > max_attempts:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many requests. Please try again later.",
        )


def get_current_user(
    creds: HTTPAuthorizationCredentials = Depends(_bearer),
    db: Session = Depends(get_db),
) -> User:
    return _resolve_user(creds.credentials, db)


def get_current_user_optional(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer_optional),
    db: Session = Depends(get_db),
) -> User | None:
    if not creds:
        return None
    payload = decode_token(creds.credentials)
    if not payload:
        return None
    return get_user_by_id(db, int(payload.get("sub", 0)))


# ── Routes ────────────────────────────────────────────────────────────────────

@router.post("/register/start", response_model=RegisterStartResponse, status_code=202)
async def register_start(
    req: RegisterRequest,
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> RegisterStartResponse:
    """Start registration by emailing a one-time verification code."""
    _enforce_rate_limit(
        request,
        settings,
        scope="register_start",
        principal_hint=req.email,
        max_attempts=settings.AUTH_REGISTER_MAX_ATTEMPTS,
    )

    remote_ip = request.client.host if request.client else ""
    
    # Debug logging for CAPTCHA verification issues
    import logging
    logger = logging.getLogger(__name__)
    logger.info(f"[register/start] Settings: CAPTCHA_REQUIRED={settings.CAPTCHA_REQUIRED}, APP_ENV={settings.APP_ENV}")
    logger.info(f"[register/start] Received: username={req.username}, email={req.email}, token_present={bool(req.captcha_token)}, remote_ip={remote_ip}")
    
    if not await verify_captcha(req.captcha_token, remote_ip=remote_ip):
        logger.warning(f"[register/start] CAPTCHA verification failed for email={req.email}")
        raise HTTPException(status_code=400, detail="CAPTCHA verification failed")

    if get_user_by_username(db, req.username):
        raise HTTPException(status_code=400, detail="Unable to create account with provided credentials")
    if get_user_by_email(db, req.email):
        raise HTTPException(status_code=400, detail="Unable to create account with provided credentials")

    password_hash = hash_password(req.password)
    request_id, code, ttl, masked_email = create_signup_challenge(
        req.username,
        req.email,
        password_hash,
        req.boj_handle,
    )

    try:
        send_email_verification_code(req.email, req.username, code)
    except Exception:
        raise HTTPException(status_code=503, detail="Failed to send verification email")

    expose_code = settings.APP_ENV.lower() != "production"
    return RegisterStartResponse(
        message="Verification code sent. Complete registration with the code.",
        request_id=request_id,
        expires_in_seconds=ttl,
        email_masked=masked_email,
        verification_code_debug=code if expose_code else None,
    )


@router.get("/public-config")
def public_auth_config(settings: Settings = Depends(get_settings)) -> dict:
    return {
        "captcha_required": _captcha_required(settings),
        "captcha_site_key": settings.CAPTCHA_SITE_KEY,
    }


@router.post("/register/complete", response_model=TokenResponse, status_code=201)
async def register_complete(
    req: RegisterCompleteRequest,
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> TokenResponse:
    _enforce_rate_limit(
        request,
        settings,
        scope="register_complete",
        principal_hint=req.request_id,
        max_attempts=settings.AUTH_REGISTER_MAX_ATTEMPTS,
    )

    payload = consume_signup_challenge(req.request_id, req.verification_code)
    if not payload:
        raise HTTPException(status_code=400, detail="Invalid or expired verification code")

    username = str(payload.get("username", "")).lower()
    email = str(payload.get("email", "")).lower()
    password_hash = payload.get("password_hash")
    boj_handle = payload.get("boj_handle")

    if not username or not email or not password_hash:
        raise HTTPException(status_code=400, detail="Invalid registration payload")

    if get_user_by_username(db, username) or get_user_by_email(db, email):
        raise HTTPException(status_code=400, detail="Unable to create account with provided credentials")

    try:
        user = create_user_from_password_hash(
            db,
            username=username,
            email=email,
            password_hash=password_hash,
            boj_handle=boj_handle,
            email_verified=True,
        )
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=400, detail="Unable to create account with provided credentials")

    token = create_access_token(user.id)
    return TokenResponse(access_token=token, user=UserResponse.model_validate(user))


@router.post("/login", response_model=TokenResponse)
async def login(
    req: LoginRequest,
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> TokenResponse:
    """Authenticate and receive a JWT."""
    _enforce_rate_limit(
        request,
        settings,
        scope="login",
        principal_hint=req.username,
        max_attempts=settings.AUTH_LOGIN_MAX_ATTEMPTS,
    )

    remote_ip = request.client.host if request.client else ""
    
    # Debug logging for CAPTCHA verification issues
    import logging
    logger = logging.getLogger(__name__)
    logger.info(f"[login] Settings: CAPTCHA_REQUIRED={settings.CAPTCHA_REQUIRED}, APP_ENV={settings.APP_ENV}")
    logger.info(f"[login] Received: username={req.username}, token_present={bool(req.captcha_token)}, remote_ip={remote_ip}")
    
    if not await verify_captcha(req.captcha_token, remote_ip=remote_ip):
        logger.warning(f"[login] CAPTCHA verification failed for username={req.username}")
        raise HTTPException(status_code=400, detail="CAPTCHA verification failed")

    user = get_user_by_username(db, req.username)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
        )

    if is_account_locked(user):
        raise HTTPException(
            status_code=status.HTTP_423_LOCKED,
            detail="Account temporarily locked due to repeated failed login attempts.",
        )

    if not verify_password(req.password, user.password_hash):
        register_login_failure(db, user)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
        )

    if not user.email_verified:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Email verification required before login",
        )

    clear_login_failures(db, user)
    token = create_access_token(user.id)
    return TokenResponse(access_token=token, user=UserResponse.model_validate(user))


@router.get("/me", response_model=UserResponse)
def me(current_user: User = Depends(get_current_user)) -> UserResponse:
    """Return the authenticated user's profile."""
    return UserResponse.model_validate(current_user)


@router.patch("/boj-handle", response_model=UserResponse)
def update_boj_handle(
    req: UpdateHandleRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> UserResponse:
    """Link or change the user's solved.ac handle."""
    current_user.boj_handle = req.boj_handle.strip()
    db.commit()
    db.refresh(current_user)
    return UserResponse.model_validate(current_user)
