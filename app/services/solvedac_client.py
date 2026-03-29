"""
solved.ac API v3 client.

In test / offline mode the MOCK_MODE env var activates a rich
synthetic dataset so the full recommendation pipeline runs without
hitting the real API.
"""
from __future__ import annotations

import logging
import os
import random
from typing import Optional

import httpx

from app.core.config import get_settings
from app.models.problem import Problem, SolvedProblem

logger = logging.getLogger(__name__)
MOCK_MODE: bool = os.getenv("MOCK_MODE", "false").lower() in ("1", "true", "yes")

# ── Mock data definitions ──────────────────────────────────────────────────────

_MOCK_USER_RAW = {
    "handle": "bogamie",
    "tier": 13,       # Gold 3
    "rating": 1547,
    "solvedCount": 312,
    "maxStreak": 42,
    "bio": "",
}

# Rich pool of problems spanning Gold 2-5 with realistic tag distributions.
_MOCK_PROBLEM_POOL: list[dict] = [
    # ── Graph problems ──────────────────────────────────────────────────────
    {"problemId": 1197, "titleKo": "최소 스패닝 트리", "level": 13, "tags": ["graphs", "minimum_spanning_tree"]},
    {"problemId": 1753, "titleKo": "최단경로", "level": 12, "tags": ["graphs", "dijkstra", "shortest_path"]},
    {"problemId": 11657, "titleKo": "타임머신", "level": 12, "tags": ["graphs", "bellman_ford", "shortest_path"]},
    {"problemId": 1916, "titleKo": "최소비용 구하기", "level": 12, "tags": ["graphs", "dijkstra", "shortest_path"]},
    {"problemId": 11404, "titleKo": "플로이드", "level": 13, "tags": ["graphs", "floyd_warshall", "shortest_path"]},
    {"problemId": 2206, "titleKo": "벽 부수고 이동하기", "level": 14, "tags": ["graphs", "bfs", "implementation"]},
    {"problemId": 7569, "titleKo": "토마토 (3D)", "level": 13, "tags": ["graphs", "bfs", "implementation"]},
    {"problemId": 1167, "titleKo": "트리의 지름", "level": 13, "tags": ["graphs", "trees", "dfs"]},
    {"problemId": 1516, "titleKo": "게임 개발", "level": 13, "tags": ["graphs", "topological_sorting"]},
    {"problemId": 2252, "titleKo": "줄 세우기", "level": 12, "tags": ["graphs", "topological_sorting"]},
    # ── DP problems ─────────────────────────────────────────────────────────
    {"problemId": 1520, "titleKo": "내리막 길", "level": 13, "tags": ["dp", "graphs", "dfs"]},
    {"problemId": 11049, "titleKo": "행렬 곱셈 순서", "level": 13, "tags": ["dp"]},
    {"problemId": 1912, "titleKo": "연속합", "level": 11, "tags": ["dp"]},
    {"problemId": 9251, "titleKo": "LCS", "level": 12, "tags": ["dp", "string"]},
    {"problemId": 11054, "titleKo": "가장 긴 바이토닉 부분 수열", "level": 13, "tags": ["dp"]},
    {"problemId": 1007, "titleKo": "벡터 매칭", "level": 15, "tags": ["dp", "divide_and_conquer", "math"]},
    {"problemId": 12865, "titleKo": "평범한 배낭", "level": 12, "tags": ["dp", "knapsack"]},
    {"problemId": 2293, "titleKo": "동전 1", "level": 12, "tags": ["dp"]},
    {"problemId": 11055, "titleKo": "가장 큰 증가하는 부분 수열", "level": 11, "tags": ["dp"]},
    {"problemId": 1149, "titleKo": "RGB거리", "level": 11, "tags": ["dp"]},
    # ── Binary Search problems ───────────────────────────────────────────────
    {"problemId": 1654, "titleKo": "랜선 자르기", "level": 11, "tags": ["binary_search", "parametric_search"]},
    {"problemId": 2805, "titleKo": "나무 자르기", "level": 11, "tags": ["binary_search", "parametric_search"]},
    {"problemId": 2110, "titleKo": "공유기 설치", "level": 13, "tags": ["binary_search", "parametric_search"]},
    {"problemId": 1300, "titleKo": "K번째 수", "level": 13, "tags": ["binary_search", "math"]},
    {"problemId": 6236, "titleKo": "용돈 관리", "level": 11, "tags": ["binary_search", "parametric_search"]},
    {"problemId": 3079, "titleKo": "입국심사", "level": 12, "tags": ["binary_search", "parametric_search"]},
    {"problemId": 1920, "titleKo": "수 찾기", "level": 7, "tags": ["binary_search", "sorting"]},
    # ── Implementation problems ──────────────────────────────────────────────
    {"problemId": 14891, "titleKo": "톱니바퀴", "level": 13, "tags": ["implementation", "simulation"]},
    {"problemId": 14503, "titleKo": "로봇 청소기", "level": 13, "tags": ["implementation", "simulation"]},
    {"problemId": 15685, "titleKo": "드래곤 커브", "level": 14, "tags": ["implementation", "simulation"]},
    {"problemId": 15686, "titleKo": "치킨 배달", "level": 13, "tags": ["implementation", "bfs", "brute_force"]},
    {"problemId": 14502, "titleKo": "연구소", "level": 13, "tags": ["implementation", "bfs", "brute_force"]},
    {"problemId": 17144, "titleKo": "미세먼지 안녕!", "level": 14, "tags": ["implementation", "simulation"]},
    {"problemId": 16234, "titleKo": "인구 이동", "level": 14, "tags": ["implementation", "bfs", "simulation"]},
    # ── Greedy / Mixed ───────────────────────────────────────────────────────
    {"problemId": 1092, "titleKo": "배", "level": 12, "tags": ["greedy", "sorting"]},
    {"problemId": 11399, "titleKo": "ATM", "level": 10, "tags": ["greedy", "sorting"]},
    {"problemId": 13305, "titleKo": "주유소", "level": 11, "tags": ["greedy"]},
    {"problemId": 1931, "titleKo": "회의실 배정", "level": 11, "tags": ["greedy", "sorting"]},
    {"problemId": 10610, "titleKo": "30", "level": 11, "tags": ["greedy", "math", "string"]},
]

# Simulate which problems the mock user has already solved.
# User is Gold 3 but inactive; they have NOT solved several high-value Gold problems.
_MOCK_SOLVED_IDS = {
    1912, 9251, 11055, 1149,   # easy DP
    1654, 2805, 1920,          # easier binary search
    11399, 1931,               # easy greedy
    1753,                      # basic Dijkstra
    2252,                      # basic topological sort
}

# Simulate tags the user attempted but failed (WA only)
_MOCK_WA_TAGS = {"floyd_warshall", "minimum_spanning_tree", "parametric_search"}


def _make_solved(p: dict) -> SolvedProblem:
    return SolvedProblem(
        problem=Problem(
            problem_id=p["problemId"],
            title=p["titleKo"],
            tier=p["level"],
            tags=p["tags"],
        ),
        solved_at="2025-08-01T00:00:00",  # ~8 months ago (inactive user)
        language="C++17",
        result="AC",
    )


# ── Real HTTP client ───────────────────────────────────────────────────────────

class SolvedACClient:
    """
    Thin async wrapper around solved.ac API v3.
    Falls back to rich mock data when MOCK_MODE=true or the real API is unreachable.
    """

    def __init__(self) -> None:
        self.settings = get_settings()
        self._http: Optional[httpx.AsyncClient] = None

    async def _client(self) -> httpx.AsyncClient:
        if self._http is None or self._http.is_closed:
            self._http = httpx.AsyncClient(
                base_url=self.settings.SOLVEDAC_API_BASE,
                timeout=self.settings.SOLVEDAC_REQUEST_TIMEOUT,
                headers={"Accept": "application/json"},
            )
        return self._http

    async def close(self) -> None:
        if self._http and not self._http.is_closed:
            await self._http.aclose()

    # ── Public API ─────────────────────────────────────────────────────────

    async def get_user(self, handle: str) -> dict:
        """Return raw user info dict from solved.ac."""
        if MOCK_MODE:
            return {**_MOCK_USER_RAW, "handle": handle}
        client = await self._client()
        resp = await client.get(f"/user/show", params={"handle": handle})
        resp.raise_for_status()
        return resp.json()

    async def get_solved_problems(
        self, handle: str, *, page: int = 1, limit: int = 50
    ) -> list[SolvedProblem]:
        """
        Return problems the user has solved.
        Real API: GET /search/problem?query=solved_by:{handle}&sort=id&direction=desc
        We cap at `limit` for performance; a full crawl would paginate.
        """
        if MOCK_MODE:
            return [
                _make_solved(p)
                for p in _MOCK_PROBLEM_POOL
                if p["problemId"] in _MOCK_SOLVED_IDS
            ]
        client = await self._client()
        resp = await client.get(
            "/search/problem",
            params={
                "query": f"solved_by:{handle}",
                "sort": "id",
                "direction": "desc",
                "page": page,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        problems = []
        for item in data.get("items", [])[:limit]:
            tags = [t["key"] for t in item.get("tags", [])]
            problems.append(
                SolvedProblem(
                    problem=Problem(
                        problem_id=item["problemId"],
                        title=item.get("titleKo", ""),
                        tier=item.get("level", 0),
                        tags=tags,
                        accepted_user_count=item.get("acceptedUserCount", 0),
                        average_tries=item.get("averageTries", 0.0),
                    ),
                    language="C++",
                    result="AC",
                )
            )
        return problems

    async def get_candidate_problems(
        self, tier_min: int, tier_max: int, tags: list[str], *, handle: Optional[str] = None
    ) -> list[Problem]:
        """
        Fetch unsolved candidate problems in the given tier band.
        In mock mode we return everything from the pool not already solved.
        """
        if MOCK_MODE:
            return [
                Problem(
                    problem_id=p["problemId"],
                    title=p["titleKo"],
                    tier=p["level"],
                    tags=p["tags"],
                )
                for p in _MOCK_PROBLEM_POOL
                if p["problemId"] not in _MOCK_SOLVED_IDS
                and tier_min <= p["level"] <= tier_max
            ]
        # Real API: tag filters can be too restrictive (AND semantics),
        # so we progressively relax the query to avoid empty recommendations.
        client = await self._client()
        unsolved_filter = f"-solved_by:{handle}" if handle else ""

        tag_query = " ".join(f"#{tag}" for tag in tags[:3]) if tags else ""
        strict_query = f"tier:{tier_min}..{tier_max} {tag_query} {unsolved_filter}".strip()
        relaxed_query = f"tier:{tier_min}..{tier_max} {unsolved_filter}".strip()
        widened_query = f"tier:{max(1, tier_min - 1)}..{tier_max + 1} {unsolved_filter}".strip()

        queries = [strict_query]
        if relaxed_query not in queries:
            queries.append(relaxed_query)
        if widened_query not in queries:
            queries.append(widened_query)

        last_count = 0
        for query in queries:
            resp = await client.get(
                "/search/problem",
                params={"query": query, "sort": "solved", "direction": "desc", "page": 1},
            )
            resp.raise_for_status()
            data = resp.json()
            items = data.get("items", [])
            last_count = int(data.get("count", 0) or 0)

            if not items:
                continue

            problems = []
            for item in items:
                tags_list = [t["key"] for t in item.get("tags", [])]
                problems.append(
                    Problem(
                        problem_id=item["problemId"],
                        title=item.get("titleKo", ""),
                        tier=item.get("level", 0),
                        tags=tags_list,
                        accepted_user_count=item.get("acceptedUserCount", 0),
                        average_tries=item.get("averageTries", 0.0),
                    )
                )

            logger.info(
                "Candidate search succeeded with query='%s' (count=%s, returned=%s)",
                query,
                last_count,
                len(problems),
            )
            return problems

        logger.info(
            "Candidate search returned no items after fallback (last_count=%s, tier=%s..%s, handle=%s)",
            last_count,
            tier_min,
            tier_max,
            handle,
        )
        return []
