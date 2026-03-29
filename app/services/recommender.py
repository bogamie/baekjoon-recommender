"""
Recommendation Engine — Core of the system.

Scoring function (all components in [0, 1]):

    score = W_diff  * difficulty_score(p)
          + W_weak  * weakness_score(p)
          + W_prio  * tag_priority_score(p)
          + W_expl  * exploration_bonus(p)
          * recency_multiplier(profile)

Component breakdown:
─────────────────────────────────────────────────────────────────
difficulty_score   — Gaussian centred on user_tier + 1.
                     Problems slightly above current level are
                     optimal for improvement (Vygotsky's ZPD).

weakness_score     — Max weakness weight across all problem tags.
                     Weakness = 1 - success_rate, amplified when
                     the tag is in the PRIORITY_TAGS list.

tag_priority_score — Binary boost for problems tagged with any of
                     the user's explicitly targeted tags (Graph,
                     DP, Binary Search, Implementation).

exploration_bonus  — Small bonus for problems the user hasn't
                     touched at all (pure unseen tags), to prevent
                     over-exploitation of already-decent areas.

recency_multiplier — Reduces the effective tier window when the
                     user is inactive, ensuring warm-up before
                     stretch problems.
─────────────────────────────────────────────────────────────────
"""
from __future__ import annotations

import logging
import math
import random
from dataclasses import dataclass, field
from typing import Optional

from app.core.config import get_settings
from app.models.problem import Problem
from app.models.user import UserProfile

logger = logging.getLogger(__name__)


@dataclass
class Recommendation:
    """A single recommended problem with scoring metadata."""
    problem: Problem
    score: float
    reason: str
    expected_outcome: str
    # Sub-scores exposed for transparency / debugging
    difficulty_score: float = 0.0
    weakness_score: float = 0.0
    tag_priority_score: float = 0.0
    exploration_bonus: float = 0.0
    recency_multiplier: float = 1.0


class RecommendationEngine:
    """
    Rule-based engine that ranks candidate problems for a given user.

    Design rationale: rule-based over ML because:
      1. We have limited labelled data (one user).
      2. Rules are transparent and debuggable.
      3. Easy to tune weights without retraining.
    An ML upgrade path (collaborative filtering / LightGBM ranker)
    is straightforward — replace `score_problem` with a model call.
    """

    def __init__(self) -> None:
        self.settings = get_settings()
        self._priority_tags = set(self.settings.PRIORITY_TAGS)

    # ── Public ────────────────────────────────────────────────────────────────

    def recommend(
        self,
        profile: UserProfile,
        candidates: list[Problem],
        n: int = 10,
        *,
        seed: Optional[int] = None,
    ) -> list[Recommendation]:
        """
        Score all candidates and return the top-n recommendations.

        A small random shuffle before sorting ensures we don't always
        return the exact same ordering for equally-scored problems
        (exploration / variety).
        """
        if seed is not None:
            random.seed(seed)

        scored = [self._score_problem(p, profile) for p in candidates]
        # Shuffle for tie-breaking variety, then stable-sort descending
        random.shuffle(scored)
        scored.sort(key=lambda r: r.score, reverse=True)
        return scored[:n]

    # ── Scoring ───────────────────────────────────────────────────────────────

    def _score_problem(self, problem: Problem, profile: UserProfile) -> Recommendation:
        diff_s = self._difficulty_score(problem.tier, profile)
        weak_s = self._weakness_score(problem.tags, profile)
        prio_s = self._tag_priority_score(problem.tags)
        expl_s = self._exploration_bonus(problem.tags, profile)
        rec_m = self._recency_multiplier(profile, problem.tier)

        raw_score = (
            self.settings.WEIGHT_DIFFICULTY   * diff_s
            + self.settings.WEIGHT_WEAKNESS   * weak_s
            + self.settings.WEIGHT_TAG_PRIORITY * prio_s
            + self.settings.WEIGHT_EXPLORATION * expl_s
        ) * rec_m

        reason, outcome = self._explain(problem, profile, diff_s, weak_s, prio_s, expl_s)

        return Recommendation(
            problem=problem,
            score=round(raw_score, 4),
            reason=reason,
            expected_outcome=outcome,
            difficulty_score=round(diff_s, 4),
            weakness_score=round(weak_s, 4),
            tag_priority_score=round(prio_s, 4),
            exploration_bonus=round(expl_s, 4),
            recency_multiplier=round(rec_m, 4),
        )

    # ── Component functions ───────────────────────────────────────────────────

    def _difficulty_score(self, tier: int, profile: UserProfile) -> float:
        """
        Gaussian centred at user_tier + 1 with σ = 1.5.
        Peak score (1.0) when the problem is exactly one tier above the user.
        Drops off sharply for very easy (<< user tier) or very hard (>> +3).

        Why Gaussian and not a linear window?
          A window gives a flat score within bounds and zero outside.
          A Gaussian smoothly rewards problems near the sweet spot and
          still gives partial credit to slightly outside problems.
        """
        optimal_delta = 1.0  # ideal: one tier harder than current
        sigma = 1.5
        delta = tier - profile.tier
        return math.exp(-((delta - optimal_delta) ** 2) / (2 * sigma ** 2))

    def _weakness_score(self, tags: list[str], profile: UserProfile) -> float:
        """
        Returns the maximum weakness weight across all problem tags.

        weakness(tag) = (1 - success_rate) * priority_amplifier

        priority_amplifier = 1.5 if tag in PRIORITY_TAGS else 1.0
        This makes sure a 70%-success tag in Graph scores higher than
        a 70%-success tag in some niche area the user doesn't need.
        """
        if not tags:
            return 0.0

        max_weakness = 0.0
        for tag in tags:
            stat = profile.tag_stats.get(tag)
            if stat is None:
                # Unseen tag: treat as total weakness (0% success rate)
                raw = 1.0
            else:
                raw = 1.0 - stat.success_rate  # 0=strong, 1=complete weakness

            amplifier = 1.5 if tag in self._priority_tags else 1.0
            max_weakness = max(max_weakness, min(1.0, raw * amplifier))

        return max_weakness

    def _tag_priority_score(self, tags: list[str]) -> float:
        """
        1.0 if ANY tag matches user's priority list, else 0.0.
        Simple binary — avoids over-rewarding multi-tag priority problems
        at the expense of single-tag ones.
        """
        return 1.0 if any(t in self._priority_tags for t in tags) else 0.0

    def _exploration_bonus(self, tags: list[str], profile: UserProfile) -> float:
        """
        Fraction of this problem's tags that the user has NEVER attempted.
        Encourages encountering new territory rather than grinding solved areas.
        """
        if not tags:
            return 0.0
        unseen = sum(1 for t in tags if t not in profile.tag_stats)
        return unseen / len(tags)

    def _recency_multiplier(self, profile: UserProfile, problem_tier: Optional[int] = None) -> float:
        """
        Tier-sensitive inactivity penalty.

        During long inactivity, harder-than-current-tier problems receive
        a stronger penalty while same/easier tiers are left untouched.
        This changes ranking (not just absolute scores) and makes warm-up
        progression more realistic.
        """
        if problem_tier is None:
            problem_tier = profile.tier + 1

        MIN_M = 0.70
        days = profile.days_since_last_solve
        if days <= self.settings.INACTIVITY_THRESHOLD_DAYS:
            return 1.0

        # inactivity severity: 0.0 at threshold, 1.0 at ~180 days and beyond
        excess = days - self.settings.INACTIVITY_THRESHOLD_DAYS
        severity = min(1.0, excess / 120.0)

        delta = problem_tier - profile.tier
        if delta <= 0:
            return 1.0

        # Higher deltas should decay faster during inactivity.
        tier_factor = min(1.0, delta / 3.0)
        penalty = severity * tier_factor * (1.0 - MIN_M)
        return max(MIN_M, 1.0 - penalty)

    # ── Explanation generation ────────────────────────────────────────────────

    def _explain(
        self,
        problem: Problem,
        profile: UserProfile,
        diff_s: float,
        weak_s: float,
        prio_s: float,
        expl_s: float,
    ) -> tuple[str, str]:
        """
        Generate a human-readable reason and expected learning outcome
        by inspecting which scoring component dominated.
        """
        components = {
            "difficulty": diff_s,
            "weakness":   weak_s,
            "priority":   prio_s,
            "exploration":expl_s,
        }
        dominant = max(components, key=components.get)  # type: ignore

        tier_label = problem.tier_label
        tags_str = ", ".join(problem.tags[:3])

        # Identify the user's weak tag(s) among this problem's tags
        weak_overlap = [
            t for t in problem.tags
            if t in profile.weak_tags[:5] or t not in profile.tag_stats
        ]

        if dominant == "difficulty":
            tier_delta = problem.tier - profile.tier
            if tier_delta > 0:
                reason = (
                    f"적정 도전 난이도입니다. 현재 실력보다 +{tier_delta}티어 높은 "
                    f"{tier_label} 문제로 안정적인 성장을 기대할 수 있습니다."
                )
                outcome = f"적당한 난도로 실전 해결력을 강화합니다. 관련 태그: {tags_str}."
            else:
                reason = (
                    f"장기 공백 이후 감각 회복에 적합한 {tier_label} 워밍업 문제입니다."
                )
                outcome = f"구현 속도와 패턴 회상력을 회복합니다. 관련 태그: {tags_str}."

        elif dominant == "weakness":
            wt = weak_overlap[0] if weak_overlap else (problem.tags[0] if problem.tags else "unknown")
            reason = (
                f"취약 영역 '{wt}' 보완에 초점을 둔 문제입니다. "
                f"성공률이 낮은 패턴을 직접 훈련할 수 있습니다."
            )
            outcome = (
                f"{wt} 유형의 패턴 인식과 풀이 자신감을 높일 수 있습니다."
            )

        elif dominant == "priority":
            reason = (
                f"우선 학습 태그 [{tags_str}]를 포함해 "
                f"핵심 출제 유형 대비에 적합합니다."
            )
            outcome = (
                f"{problem.tags[0]} 핵심 역량을 강화합니다."
            )

        else:  # exploration
            reason = (
                f"아직 충분히 다루지 않은 태그 [{tags_str}]를 경험해 "
                f"풀이 범위를 넓힐 수 있습니다."
            )
            outcome = (
                f"{problem.tags[0]} 유형을 새롭게 익혀 알고리즘 도구를 확장합니다."
            )

        return reason, outcome
