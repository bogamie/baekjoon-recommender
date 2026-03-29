"""
Unit tests for the recommendation engine and analyzer.
No external I/O — uses the mock client and in-process services.
"""
from __future__ import annotations

import os
import pytest

os.environ["MOCK_MODE"] = "true"

from app.models.problem import Problem, SolvedProblem
from app.models.user import UserProfile, TagStat
from app.services.analyzer import UserAnalyzer
from app.services.recommender import RecommendationEngine
from app.services.solvedac_client import SolvedACClient, _MOCK_USER_RAW


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def mock_user_raw():
    return {**_MOCK_USER_RAW}


@pytest.fixture
def analyzer():
    return UserAnalyzer()


@pytest.fixture
def engine():
    return RecommendationEngine()


@pytest.fixture
def solved_problems():
    return [
        SolvedProblem(
            problem=Problem(problem_id=1912, title="연속합", tier=11, tags=["dp"]),
            solved_at="2025-08-01T00:00:00",
            result="AC",
        ),
        SolvedProblem(
            problem=Problem(problem_id=9251, title="LCS", tier=12, tags=["dp", "string"]),
            solved_at="2025-08-01T00:00:00",
            result="AC",
        ),
        SolvedProblem(
            problem=Problem(problem_id=1753, title="최단경로", tier=12, tags=["graphs", "dijkstra"]),
            solved_at="2025-08-01T00:00:00",
            result="AC",
        ),
    ]


@pytest.fixture
def profile(analyzer, mock_user_raw, solved_problems):
    analysis = analyzer.analyze(mock_user_raw, solved_problems)
    return analysis.profile


# ── Analyzer tests ────────────────────────────────────────────────────────────

class TestUserAnalyzer:
    def test_profile_tier_matches_raw(self, profile):
        assert profile.tier == 13  # Gold 3

    def test_tag_stats_populated(self, profile):
        assert "dp" in profile.tag_stats
        assert "graphs" in profile.tag_stats

    def test_dp_success_rate_is_smoothed(self, profile):
        # With Laplace smoothing: (solved+1)/(attempted+2) => (2+1)/(2+2)=0.75
        assert profile.tag_stats["dp"].success_rate == pytest.approx(0.75)

    def test_weak_tags_non_empty(self, profile):
        assert len(profile.weak_tags) > 0

    def test_inactivity_detected(self, analyzer, mock_user_raw, solved_problems):
        # Mock data has solved_at = 2025-08-01 (~8 months ago from 2026-03)
        analysis = analyzer.analyze(mock_user_raw, solved_problems)
        assert analysis.inactivity_warning is True

    def test_tier_window_with_inactivity(self, profile):
        # With inactivity penalty=1 and tier=13:
        # min = 13 + (-1) - 1 = 11, max = 13 + 2 - 1 = 14
        assert profile.recommended_tier_min == 11
        assert profile.recommended_tier_max == 14

    def test_unseen_tags_contain_missing_priority(self, profile):
        # binary_search not in solved_problems → should appear in unseen
        assert "binary_search" in profile.unseen_tags or "binary_search" in profile.weak_tags


# ── Engine tests ──────────────────────────────────────────────────────────────

class TestRecommendationEngine:
    def test_returns_n_recommendations(self, engine, profile):
        candidates = [
            Problem(problem_id=2110, title="공유기 설치", tier=13, tags=["binary_search"]),
            Problem(problem_id=11049, title="행렬 곱셈", tier=13, tags=["dp"]),
            Problem(problem_id=2206, title="벽 부수고", tier=14, tags=["bfs", "implementation"]),
            Problem(problem_id=14891, title="톱니바퀴", tier=13, tags=["implementation"]),
            Problem(problem_id=1520, title="내리막 길", tier=13, tags=["dp", "dfs"]),
        ]
        recs = engine.recommend(profile, candidates, n=3)
        assert len(recs) == 3

    def test_scores_between_0_and_1(self, engine, profile):
        candidates = [
            Problem(problem_id=2110, title="공유기 설치", tier=13, tags=["binary_search"]),
        ]
        recs = engine.recommend(profile, candidates, n=1)
        assert 0.0 <= recs[0].score <= 1.0

    def test_sorted_descending(self, engine, profile):
        candidates = [
            Problem(problem_id=2110, title="공유기", tier=13, tags=["binary_search"]),
            Problem(problem_id=1912, title="연속합", tier=11, tags=["dp"]),
            Problem(problem_id=2206, title="벽", tier=14, tags=["bfs"]),
        ]
        recs = engine.recommend(profile, candidates, n=3, seed=42)
        scores = [r.score for r in recs]
        assert scores == sorted(scores, reverse=True)

    def test_difficulty_score_peaks_near_user_tier(self, engine, profile):
        # Problem at user_tier + 1 should score higher than user_tier + 5
        optimal = Problem(problem_id=1, title="A", tier=14, tags=[])  # tier+1
        too_hard = Problem(problem_id=2, title="B", tier=18, tags=[])  # tier+5
        s_opt = engine._difficulty_score(optimal.tier, profile)
        s_hard = engine._difficulty_score(too_hard.tier, profile)
        assert s_opt > s_hard

    def test_weakness_score_higher_for_unseen_tag(self, engine, profile):
        # binary_search is unseen (100% weakness) vs dp (seen, 100% success)
        unseen = engine._weakness_score(["binary_search"], profile)
        strong = engine._weakness_score(["dp"], profile)
        assert unseen > strong

    def test_recency_multiplier_below_1_when_inactive(self, engine, profile):
        # profile has 240+ days of inactivity
        m = engine._recency_multiplier(profile)
        assert m < 1.0

    def test_reason_and_outcome_non_empty(self, engine, profile):
        candidates = [
            Problem(problem_id=2110, title="공유기 설치", tier=13, tags=["binary_search"]),
        ]
        recs = engine.recommend(profile, candidates, n=1)
        assert recs[0].reason
        assert recs[0].expected_outcome


# ── API integration (no real HTTP) ────────────────────────────────────────────

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


class TestAPI:
    def test_health(self):
        resp = client.get("/api/v1/health")
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"

    def test_analysis_returns_200(self):
        resp = client.get("/api/v1/analysis?handle=bogamie")
        assert resp.status_code == 200
        data = resp.json()
        assert "profile" in data
        assert data["profile"]["handle"] == "bogamie"

    def test_recommend_returns_recommendations(self):
        resp = client.get("/api/v1/recommend?handle=bogamie&n=5")
        assert resp.status_code == 200
        data = resp.json()
        assert data["count"] <= 5
        assert len(data["recommendations"]) == data["count"]

    def test_recommend_has_boj_url(self):
        resp = client.get("/api/v1/recommend?handle=bogamie&n=1")
        assert resp.status_code == 200
        rec = resp.json()["recommendations"][0]
        assert rec["problem"]["boj_url"].startswith("https://www.acmicpc.net/problem/")

    def test_recommend_scores_descending(self):
        resp = client.get("/api/v1/recommend?handle=bogamie&n=10")
        scores = [r["score"] for r in resp.json()["recommendations"]]
        assert scores == sorted(scores, reverse=True)
