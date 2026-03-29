"""
Database setup — SQLite via SQLAlchemy (synchronous).

SQLite is fine for a single-user / small-team service. Upgrading to
PostgreSQL requires only changing DATABASE_URL; the ORM layer stays identical.
"""
from __future__ import annotations

import os
from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

# Resolve storage path — inside Docker this will be a mounted volume.
_data_dir = Path(os.getenv("DATA_DIR", "./data"))
_data_dir.mkdir(parents=True, exist_ok=True)

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    f"sqlite:///{_data_dir}/ps_recommender.db",
)

engine = create_engine(
    DATABASE_URL,
    # Required for SQLite multi-thread use inside FastAPI
    connect_args={"check_same_thread": False} if "sqlite" in DATABASE_URL else {},
    echo=os.getenv("DB_ECHO", "false").lower() == "true",
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    pass


def get_db():
    """FastAPI dependency — yields a DB session, guarantees close."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    """Create all tables if they don't exist. Called once at startup."""
    # Import models here so SQLAlchemy sees them before create_all
    import app.models.auth_models  # noqa: F401
    Base.metadata.create_all(bind=engine)

    # Lightweight schema backfill for SQLite-only local deployments.
    if "sqlite" not in DATABASE_URL:
        return

    with engine.begin() as conn:
        columns = {
            row[1]
            for row in conn.exec_driver_sql("PRAGMA table_info(users)").fetchall()
        }

        if "email_verified" not in columns:
            conn.exec_driver_sql(
                "ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT 0"
            )
        if "failed_login_attempts" not in columns:
            conn.exec_driver_sql(
                "ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0"
            )
        if "locked_until" not in columns:
            conn.exec_driver_sql(
                "ALTER TABLE users ADD COLUMN locked_until DATETIME"
            )
