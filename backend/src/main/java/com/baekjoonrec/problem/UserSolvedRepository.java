package com.baekjoonrec.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserSolvedRepository extends JpaRepository<UserSolved, UserSolvedId> {
    List<UserSolved> findByUserId(Long userId);
    boolean existsByUserIdAndProblemId(Long userId, Integer problemId);
    long countByUserId(Long userId);
    void deleteByUserId(Long userId);

    boolean existsByUserId(Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT us.problemId FROM UserSolved us WHERE us.userId = :userId")
    List<Integer> findProblemIdsByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(DISTINCT CAST(us.solvedAt AS DATE)) FROM UserSolved us WHERE us.userId = :userId")
    long countDistinctSolvedAtByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
