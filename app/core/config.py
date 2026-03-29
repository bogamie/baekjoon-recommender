from functools import lru_cache
from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # ── solved.ac API ────────────────────────────────────────────────────────
    SOLVEDAC_API_BASE: str = "https://solved.ac/api/v3"
    SOLVEDAC_REQUEST_TIMEOUT: int = 10  # seconds

    # ── Default user (can be overridden per-request) ─────────────────────────
    DEFAULT_HANDLE: str = "tourist"

    # ── Recommendation engine knobs ──────────────────────────────────────────
    # Tier window: recommend problems within [user_tier + MIN_GAP, user_tier + MAX_GAP]
    TIER_GAP_MIN: int = -1   # one level below (review)
    TIER_GAP_MAX: int = 2    # two levels above (stretch)
    # How many recommendations to return
    DEFAULT_REC_COUNT: int = 10
    # Tags that are always prioritised for the target user profile
    PRIORITY_TAGS: list[str] = [
        "graphs", "dp", "binary_search", "implementation",
        "bfs", "dfs", "dijkstra", "shortest_path",
        "trees", "greedy",
    ]
    # Days of inactivity before we apply skill-degradation penalty
    INACTIVITY_THRESHOLD_DAYS: int = 60
    # When inactive, shift the recommended tier window down by this amount
    INACTIVITY_TIER_PENALTY: int = 1

    # ── Scoring weights (must sum to 1.0) ────────────────────────────────────
    WEIGHT_DIFFICULTY: float = 0.35
    WEIGHT_WEAKNESS:   float = 0.30
    WEIGHT_TAG_PRIORITY: float = 0.20
    WEIGHT_EXPLORATION:  float = 0.15

    # ── Redis (optional caching) ─────────────────────────────────────────────
    REDIS_URL: Optional[str] = None
    CACHE_TTL_SECONDS: int = 300  # 5 minutes

    # ── Auth / JWT ───────────────────────────────────────────────────────────
    SECRET_KEY: str = "CHANGE-THIS-IN-PRODUCTION-use-32+-random-chars"
    JWT_ALGORITHM: str = "HS256"
    JWT_EXPIRE_DAYS: int = 7
    AUTH_RATE_LIMIT_WINDOW_SECONDS: int = 60
    AUTH_REGISTER_MAX_ATTEMPTS: int = 15
    AUTH_LOGIN_MAX_ATTEMPTS: int = 30
    AUTH_LOGIN_MAX_FAILURES: int = 5
    AUTH_ACCOUNT_LOCK_MINUTES: int = 15
    EMAIL_VERIFICATION_TTL_HOURS: int = 24
    EMAIL_CODE_TTL_MINUTES: int = 10
    CAPTCHA_REQUIRED: bool = False
    CAPTCHA_SITE_KEY: Optional[str] = None
    CAPTCHA_SECRET_KEY: Optional[str] = None
    CAPTCHA_VERIFY_URL: str = "https://www.google.com/recaptcha/api/siteverify"

    # SMTP (Brevo-compatible)
    SMTP_HOST: Optional[str] = None
    SMTP_PORT: int = 587
    SMTP_USERNAME: Optional[str] = None
    SMTP_PASSWORD: Optional[str] = None
    SMTP_FROM_EMAIL: Optional[str] = None
    SMTP_FROM_NAME: str = "PS Recommender"
    SMTP_USE_TLS: bool = True

    # ── App ──────────────────────────────────────────────────────────────────
    APP_ENV: str = "development"
    LOG_LEVEL: str = "INFO"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return a cached singleton Settings instance."""
    return Settings()
