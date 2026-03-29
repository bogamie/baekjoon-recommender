"""
UserAnalyzer: transforms raw solved.ac data into a structured UserProfile.

Responsibilities:
  - Tag-level success rate computation
  - Recency / inactivity detection
  - Skill-degradation estimation
  - Recommended tier window calculation
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Optional

from app.core.config import get_settings
from app.models.problem import SolvedProblem
from app.models.user import AnalysisResult, TagStat, UserProfile

logger = logging.getLogger(__name__)


def _days_since(iso_str: Optional[str]) -> int:
    """Return days elapsed since an ISO-8601 datetime string, or 0 if None."""
    if not iso_str:
        return 0
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        # Ensure dt is timezone-aware; if naive, assume UTC
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        now = datetime.now(tz=timezone.utc)
        return max(0, (now - dt).days)
    except ValueError:
        return 0


def _parse_iso_datetime(iso_str: Optional[str]) -> Optional[datetime]:
    """Parse ISO-8601 string safely into timezone-aware datetime."""
    if not iso_str:
        return None
    try:
        dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except ValueError:
        return None


class UserAnalyzer:
    def __init__(self) -> None:
        self.settings = get_settings()

    # ── Public entry point ────────────────────────────────────────────────────

    def analyze(
        self,
        raw_user: dict,
        solved_problems: list[SolvedProblem],
    ) -> AnalysisResult:
        """
        Build a complete AnalysisResult from solved.ac user data +
        the user's solved problem list.
        """
        profile = self._build_profile(raw_user, solved_problems)
        return self._build_analysis(profile)

    # ── Profile construction ──────────────────────────────────────────────────

    def _build_profile(
        self,
        raw_user: dict,
        solved_problems: list[SolvedProblem],
    ) -> UserProfile:
        handle = raw_user.get("handle", "unknown")
        tier = raw_user.get("tier", 0)
        rating = raw_user.get("rating", 0)
        solved_count = raw_user.get("solvedCount", len(solved_problems))

        # ── Tag statistics ────────────────────────────────────────────────────
        tag_stats = self._compute_tag_stats(solved_problems)

        # ── Identify the most recent solve ───────────────────────────────────
        last_solve_dt: Optional[datetime] = None
        for sp in solved_problems:
            dt = _parse_iso_datetime(sp.solved_at)
            if dt and (last_solve_dt is None or dt > last_solve_dt):
                last_solve_dt = dt
        days_since = _days_since(last_solve_dt.isoformat() if last_solve_dt else None)

        # ── Weak-tag ranking ─────────────────────────────────────────────────
        # A tag is "weak" if: low success_rate OR very few attempts relative to tier.
        # We sort ascending by a weakness score = success_rate * log(attempts+1).
        def weakness_key(ts: TagStat) -> float:
            import math
            # Lower = weaker. Penalise both low success AND low volume.
            volume_factor = math.log(ts.solved_count + 1)
            return ts.success_rate * volume_factor

        ranked = sorted(tag_stats.values(), key=weakness_key)
        weak_tags = [ts.tag for ts in ranked[:10]]

        # ── Blind-spot tags (priority tags the user has never touched) ────────
        all_seen_tags = set(tag_stats.keys())
        unseen_tags = [t for t in self.settings.PRIORITY_TAGS if t not in all_seen_tags]

        # ── Tier window ───────────────────────────────────────────────────────
        inactivity_penalty = (
            self.settings.INACTIVITY_TIER_PENALTY
            if days_since >= self.settings.INACTIVITY_THRESHOLD_DAYS
            else 0
        )
        tier_min = max(1, tier + self.settings.TIER_GAP_MIN - inactivity_penalty)
        tier_max = max(1, tier + self.settings.TIER_GAP_MAX - inactivity_penalty)

        from app.models.problem import Tier as TierEnum
        try:
            tier_label = TierEnum(tier).label
        except ValueError:
            tier_label = f"Tier {tier}"

        return UserProfile(
            handle=handle,
            tier=tier,
            tier_label=tier_label,
            rating=rating,
            solved_count=solved_count,
            days_since_last_solve=days_since,
            tag_stats=tag_stats,
            unseen_tags=unseen_tags,
            weak_tags=weak_tags,
            recommended_tier_min=tier_min,
            recommended_tier_max=tier_max,
        )

    def _compute_tag_stats(
        self, solved_problems: list[SolvedProblem]
    ) -> dict[str, TagStat]:
        """
        Compute per-tag success rate from the AC history.

        Design note: We only have AC records from solved.ac by default.
        A real system would also ingest submission history to count WA attempts.
        Here we approximate: if a problem is AC, it counts as 1 attempt + 1 solve.
        For the mock data we pre-mark some tags as having extra failures.
        """
        tag_data: dict[str, dict] = {}

        for sp in solved_problems:
            for tag in sp.problem.tags:
                if tag not in tag_data:
                    tag_data[tag] = {
                        "solved": 0, "attempted": 0, "tier_sum": 0
                    }
                tag_data[tag]["attempted"] += 1
                if sp.result == "AC":
                    tag_data[tag]["solved"] += 1
                tag_data[tag]["tier_sum"] += sp.problem.tier

        # Build TagStat objects
        stats: dict[str, TagStat] = {}
        for tag, d in tag_data.items():
            attempted = max(1, d["attempted"])
            solved = d["solved"]
            # Laplace smoothing to avoid overconfidence from sparse AC-only history.
            # With zero failure data, this keeps low-volume tags from looking "perfect".
            smoothed_success = (solved + 1) / (attempted + 2)
            stats[tag] = TagStat(
                tag=tag,
                solved_count=solved,
                attempted_count=attempted,
                success_rate=smoothed_success,
                avg_solved_tier=d["tier_sum"] / attempted,
            )
        return stats

    # ── Analysis building ─────────────────────────────────────────────────────

    def _build_analysis(self, profile: UserProfile) -> AnalysisResult:
        days = profile.days_since_last_solve
        inactive = days >= self.settings.INACTIVITY_THRESHOLD_DAYS

        if inactive:
            months = days // 30
            inactivity_msg = (
                f"약 {months}개월 동안 문제 풀이가 없습니다. "
                "감각 회복을 위해 골드 4 전후의 워밍업부터 시작해 보세요."
            )
            # Rough heuristic: >3 months ≈ 1 tier drop, >6 months ≈ 2 tier drops
            if days > 180:
                degradation = "약 2티어 하락 추정"
            elif days > 90:
                degradation = "약 1티어 하락 추정"
            else:
                degradation = "경미한 실력 저하"
        else:
            inactivity_msg = ""
            degradation = "없음"

        # Focus recommendation: top 5 weak tags that overlap with PRIORITY_TAGS
        priority_set = set(self.settings.PRIORITY_TAGS)
        focus = [
            t for t in profile.weak_tags if t in priority_set
        ][:5]
        # Append unseen priority tags
        for t in profile.unseen_tags:
            if t not in focus:
                focus.append(t)
                if len(focus) >= 5:
                    break

        summary = (
            f"{profile.handle}님은 {profile.tier_label}이며 "
            f"누적 해결 수는 {profile.solved_count}문제입니다. "
            f"권장 연습 구간은 T{profile.recommended_tier_min}–T{profile.recommended_tier_max}입니다. "
            f"취약 영역: {', '.join(focus[:3]) if focus[:3] else '특이 취약점 없음'}."
        )

        return AnalysisResult(
            profile=profile,
            inactivity_warning=inactive,
            inactivity_message=inactivity_msg,
            skill_degradation_estimate=degradation,
            focus_recommendation=focus,
            summary=summary,
        )
