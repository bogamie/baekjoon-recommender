from __future__ import annotations

from enum import IntEnum
from typing import Optional
from pydantic import BaseModel, Field


class Tier(IntEnum):
    """
    solved.ac tier encoding.
    0 = Unrated, 1-5 = Bronze 5..1, 6-10 = Silver 5..1,
    11-15 = Gold 5..1, 16-20 = Platinum 5..1, 21-25 = Diamond 5..1
    """
    UNRATED = 0
    BRONZE_5 = 1;  BRONZE_4 = 2;  BRONZE_3 = 3;  BRONZE_2 = 4;  BRONZE_1 = 5
    SILVER_5 = 6;  SILVER_4 = 7;  SILVER_3 = 8;  SILVER_2 = 9;  SILVER_1 = 10
    GOLD_5   = 11; GOLD_4   = 12; GOLD_3   = 13; GOLD_2   = 14; GOLD_1   = 15
    PLAT_5   = 16; PLAT_4   = 17; PLAT_3   = 18; PLAT_2   = 19; PLAT_1   = 20
    DIAMOND_5= 21; DIAMOND_4= 22; DIAMOND_3= 23; DIAMOND_2= 24; DIAMOND_1= 25

    @property
    def label(self) -> str:
        names = {
            0: "Unrated",
            **{i: f"Bronze {6-i}" for i in range(1, 6)},
            **{i: f"Silver {11-i}" for i in range(6, 11)},
            **{i: f"Gold {16-i}" for i in range(11, 16)},
            **{i: f"Platinum {21-i}" for i in range(16, 21)},
            **{i: f"Diamond {26-i}" for i in range(21, 26)},
        }
        return names.get(self.value, "Unknown")


class Problem(BaseModel):
    """A single BOJ problem with solved.ac metadata."""
    problem_id: int
    title: str
    tier: int = Field(..., ge=0, le=30, description="solved.ac tier (0=Unrated, 30=Ruby 1)")
    tags: list[str] = Field(default_factory=list)
    accepted_user_count: int = 0
    average_tries: float = 0.0
    is_partial: bool = False  # whether user has partially solved (WA but not AC)

    @property
    def tier_label(self) -> str:
        try:
            return Tier(self.tier).label
        except ValueError:
            return f"Tier {self.tier}"


class SolvedProblem(BaseModel):
    """A problem the user has already solved, with attempt metadata."""
    problem: Problem
    solved_at: Optional[str] = None   # ISO datetime string
    language: str = "C++"
    result: str = "AC"  # AC | WA | TLE | MLE
