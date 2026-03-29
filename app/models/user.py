from __future__ import annotations

from typing import Optional
from pydantic import BaseModel, Field


class TagStat(BaseModel):
    """Per-tag statistics derived from a user's solve history."""
    tag: str
    solved_count: int = 0
    attempted_count: int = 0
    # Weighted success rate (0.0 – 1.0); lower = more weakness
    success_rate: float = 0.0
    # Average tier of problems solved with this tag
    avg_solved_tier: float = 0.0


class UserProfile(BaseModel):
    """
    Aggregated profile used by the recommendation engine.
    Built from raw solved.ac API data by UserAnalyzer.
    """
    handle: str
    tier: int = Field(..., ge=0, le=30)
    tier_label: str = ""
    rating: int = 0
    solved_count: int = 0
    # Days since the user last submitted a correct answer
    days_since_last_solve: int = 0
    tag_stats: dict[str, TagStat] = Field(default_factory=dict)
    # Tags the user has NEVER touched – pure blind spots
    unseen_tags: list[str] = Field(default_factory=list)
    # Ranked list of weakest tags (for quick access)
    weak_tags: list[str] = Field(default_factory=list)
    # Tier range this user should be practicing in
    recommended_tier_min: int = 0
    recommended_tier_max: int = 0


class AnalysisResult(BaseModel):
    """Full analysis report returned by GET /analysis."""
    profile: UserProfile
    inactivity_warning: bool = False
    inactivity_message: str = ""
    skill_degradation_estimate: str = ""  # e.g. "~1 tier drop"
    focus_recommendation: list[str] = Field(default_factory=list)
    summary: str = ""
