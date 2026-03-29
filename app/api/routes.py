"""
FastAPI route definitions.

Endpoints:
  GET /health         — liveness probe
  GET /analysis       — full user analysis report
  GET /recommend      — ranked problem recommendations

All endpoints are async and use dependency injection for the
three core services so they're easy to mock in tests.
"""
from __future__ import annotations

import json
import logging
from typing import Annotated, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from app.api.auth_routes import get_current_user_optional
from app.core.cache import cache_get, cache_set
from app.core.config import Settings, get_settings
from app.models.auth_models import User
from app.models.user import AnalysisResult
from app.services.analyzer import UserAnalyzer
from app.services.recommender import Recommendation, RecommendationEngine
from app.services.solvedac_client import SolvedACClient

logger = logging.getLogger(__name__)
router = APIRouter()


# ── Dependency factories ───────────────────────────────────────────────────────

def get_solvedac_client() -> SolvedACClient:
    return SolvedACClient()


def get_analyzer() -> UserAnalyzer:
    return UserAnalyzer()


def get_engine() -> RecommendationEngine:
    return RecommendationEngine()


# ── Response schemas ──────────────────────────────────────────────────────────

class ProblemOut(BaseModel):
    problem_id: int
    title: str
    tier: int
    tier_label: str
    tags: list[str]
    boj_url: str


class RecommendationOut(BaseModel):
    rank: int
    problem: ProblemOut
    score: float
    reason: str
    expected_outcome: str
    # Transparent sub-scores for debugging
    sub_scores: dict[str, float]


class RecommendResponse(BaseModel):
    handle: str
    recommended_tier_range: str
    inactivity_warning: bool
    count: int
    recommendations: list[RecommendationOut]


class HealthResponse(BaseModel):
    status: str
    version: str = "1.0.0"


# ── Helpers ────────────────────────────────────────────────────────────────────

def _rec_to_out(rank: int, rec: Recommendation) -> RecommendationOut:
    p = rec.problem
    return RecommendationOut(
        rank=rank,
        problem=ProblemOut(
            problem_id=p.problem_id,
            title=p.title,
            tier=p.tier,
            tier_label=p.tier_label,
            tags=p.tags,
            boj_url=f"https://www.acmicpc.net/problem/{p.problem_id}",
        ),
        score=rec.score,
        reason=rec.reason,
        expected_outcome=rec.expected_outcome,
        sub_scores={
            "difficulty": rec.difficulty_score,
            "weakness":   rec.weakness_score,
            "tag_priority": rec.tag_priority_score,
            "exploration":  rec.exploration_bonus,
            "recency_multiplier": rec.recency_multiplier,
        },
    )


async def _fetch_and_analyze(
    handle: str,
    client: SolvedACClient,
    analyzer: UserAnalyzer,
    settings: Settings,
) -> AnalysisResult:
    """Shared logic: fetch solved.ac data, run analysis, cache result."""
    cache_key = f"analysis:{handle}"
    cached = cache_get(cache_key)
    if cached:
        logger.debug("Cache hit for %s", cache_key)
        return AnalysisResult.model_validate(cached)

    try:
        raw_user = await client.get_user(handle)
        solved = await client.get_solved_problems(handle)
    except Exception as exc:
        logger.exception("solved.ac API error for handle=%s", handle)
        raise HTTPException(status_code=502, detail=f"solved.ac API error: {exc}") from exc

    result = analyzer.analyze(raw_user, solved)
    cache_set(cache_key, result.model_dump(), ttl=settings.CACHE_TTL_SECONDS)
    return result


# ── Routes ─────────────────────────────────────────────────────────────────────

@router.get("/health", response_model=HealthResponse, tags=["Meta"])
async def health() -> HealthResponse:
    """Liveness probe — always returns 200 if the process is alive."""
    return HealthResponse(status="ok")


@router.get("/analysis", response_model=AnalysisResult, tags=["Analysis"])
async def get_analysis(
    handle: Annotated[
        Optional[str], Query(description="solved.ac handle", min_length=1, max_length=40)
    ] = None,
    settings: Settings = Depends(get_settings),
    client: SolvedACClient = Depends(get_solvedac_client),
    analyzer: UserAnalyzer = Depends(get_analyzer),
    current_user: Optional[User] = Depends(get_current_user_optional),
) -> AnalysisResult:
    """
    Return a full analysis report for the given user.

    Includes:
    - Tag-level statistics
    - Weak areas ranking
    - Inactivity warning
    - Recommended tier window
    """
    # Priority: explicit query param → authenticated user's handle → default
    resolved_handle = (
        handle
        or (current_user.boj_handle if current_user and current_user.boj_handle else None)
        or settings.DEFAULT_HANDLE
    )
    return await _fetch_and_analyze(resolved_handle, client, analyzer, settings)


@router.get("/recommend", response_model=RecommendResponse, tags=["Recommendations"])
async def get_recommendations(
    handle: Annotated[
        Optional[str], Query(description="solved.ac handle")
    ] = None,
    n: Annotated[
        int, Query(ge=1, le=50, description="Number of recommendations")
    ] = None,
    settings: Settings = Depends(get_settings),
    client: SolvedACClient = Depends(get_solvedac_client),
    analyzer: UserAnalyzer = Depends(get_analyzer),
    engine: RecommendationEngine = Depends(get_engine),
    current_user: Optional[User] = Depends(get_current_user_optional),
) -> RecommendResponse:
    """
    Return ranked problem recommendations for the given user.

    The engine:
    1. Analyzes solve history → identifies weaknesses
    2. Fetches candidates in the optimal tier window
    3. Scores each candidate with the multi-factor scoring function
    4. Returns top-n sorted by score descending
    """
    resolved_handle = (
        handle
        or (current_user.boj_handle if current_user and current_user.boj_handle else None)
        or settings.DEFAULT_HANDLE
    )
    count = n or settings.DEFAULT_REC_COUNT

    analysis = await _fetch_and_analyze(resolved_handle, client, analyzer, settings)
    profile = analysis.profile

    # Fetch candidates in recommended tier window
    try:
        candidates = await client.get_candidate_problems(
            tier_min=profile.recommended_tier_min,
            tier_max=profile.recommended_tier_max,
            tags=profile.weak_tags[:5],
            handle=resolved_handle,
        )
    except Exception as exc:
        logger.exception("Failed to fetch candidate problems")
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    if not candidates:
        raise HTTPException(
            status_code=404,
            detail=(
                f"추천 난이도 구간 T{profile.recommended_tier_min}–T{profile.recommended_tier_max}에서 "
                "후보 문제를 찾지 못했습니다."
            ),
        )

    recs = engine.recommend(profile, candidates, n=count)

    return RecommendResponse(
        handle=resolved_handle,
        recommended_tier_range=(
            f"{profile.recommended_tier_min}–{profile.recommended_tier_max}"
        ),
        inactivity_warning=analysis.inactivity_warning,
        count=len(recs),
        recommendations=[_rec_to_out(i + 1, r) for i, r in enumerate(recs)],
    )
