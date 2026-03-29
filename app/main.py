"""
Application entry point.

Startup order:
  1. init_db()  — create SQLite tables if absent
  2. Mount /static → frontend/
  3. Register /api/v1/* router (recommendation + auth)
  4. SPA catch-all → frontend/index.html
"""
from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from app.api.auth_routes import router as auth_router
from app.api.routes import router as main_router
from app.core.config import get_settings
from app.db import init_db

settings = get_settings()

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL, logging.INFO),
    format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    logging.getLogger(__name__).info("Database initialised")
    yield


app = FastAPI(
    title="PS Recommender",
    version="1.0.0",
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PATCH"],
    allow_headers=["*"],
)

# ── API routes ─────────────────────────────────────────────────────────────────
app.include_router(main_router, prefix="/api/v1")
app.include_router(auth_router, prefix="/api/v1")

# ── Static frontend assets ────────────────────────────────────────────────────
_frontend_dir = Path(__file__).parent.parent / "frontend"
if _frontend_dir.exists():
    app.mount("/static", StaticFiles(directory=str(_frontend_dir)), name="static")

    @app.get("/{full_path:path}", include_in_schema=False)
    async def spa_fallback(full_path: str) -> FileResponse:
        """Serve index.html for all non-API routes (SPA client-side routing)."""
        return FileResponse(str(_frontend_dir / "index.html"))
