from __future__ import annotations

from datetime import datetime
import re
from typing import Optional

from pydantic import BaseModel, ConfigDict, EmailStr, field_validator
from sqlalchemy import Boolean, Column, DateTime, Integer, String, func

from app.db import Base


# ── SQLAlchemy ORM model ───────────────────────────────────────────────────────

class User(Base):
    __tablename__ = "users"

    id           = Column(Integer, primary_key=True, index=True)
    username     = Column(String(50), unique=True, nullable=False, index=True)
    email        = Column(String(255), unique=True, nullable=False, index=True)
    password_hash= Column(String(255), nullable=False)
    boj_handle   = Column(String(50), nullable=True)
    email_verified = Column(Boolean, nullable=False, default=False)
    failed_login_attempts = Column(Integer, nullable=False, default=0)
    locked_until = Column(DateTime, nullable=True)
    created_at   = Column(DateTime, default=func.now(), nullable=False)


# ── Pydantic request / response schemas ───────────────────────────────────────

class RegisterRequest(BaseModel):
    username: str
    email: EmailStr
    password: str
    boj_handle: Optional[str] = None
    captcha_token: Optional[str] = None

    @field_validator("username")
    @classmethod
    def username_alnum(cls, v: str) -> str:
        if not v.replace("_", "").replace("-", "").isalnum():
            raise ValueError("Username may only contain letters, numbers, _ and -")
        if len(v) < 3 or len(v) > 30:
            raise ValueError("Username must be 3–30 characters")
        return v.lower()

    @field_validator("password")
    @classmethod
    def password_length(cls, v: str) -> str:
        if len(v) < 10:
            raise ValueError("Password must be at least 10 characters")
        if not any(ch.isalpha() for ch in v) or not any(ch.isdigit() for ch in v):
            raise ValueError("Password must include both letters and numbers")
        if any(ch.isspace() for ch in v):
            raise ValueError("Password cannot contain whitespace")
        return v

    @field_validator("boj_handle")
    @classmethod
    def validate_boj_handle_optional(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return None
        value = v.strip()
        if not value:
            return None
        if len(value) < 2 or len(value) > 30:
            raise ValueError("BOJ handle must be 2–30 characters")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise ValueError("BOJ handle may only contain letters, numbers, _ and -")
        return value


class RegisterCompleteRequest(BaseModel):
    request_id: str
    verification_code: str

    @field_validator("request_id")
    @classmethod
    def request_id_not_empty(cls, v: str) -> str:
        value = v.strip()
        if not value:
            raise ValueError("request_id is required")
        return value

    @field_validator("verification_code")
    @classmethod
    def verification_code_format(cls, v: str) -> str:
        value = v.strip()
        if not re.fullmatch(r"\d{6}", value):
            raise ValueError("verification_code must be 6 digits")
        return value


class LoginRequest(BaseModel):
    username: str
    password: str
    captcha_token: Optional[str] = None


class UpdateHandleRequest(BaseModel):
    boj_handle: str

    @field_validator("boj_handle")
    @classmethod
    def handle_not_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("BOJ handle cannot be empty")
        if len(v) < 2 or len(v) > 30:
            raise ValueError("BOJ handle must be 2–30 characters")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", v):
            raise ValueError("BOJ handle may only contain letters, numbers, _ and -")
        return v


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    email: str
    email_verified: bool
    boj_handle: Optional[str]
    created_at: datetime


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserResponse


class RegisterStartResponse(BaseModel):
    message: str
    request_id: str
    expires_in_seconds: int
    email_masked: str
    verification_code_debug: Optional[str] = None
