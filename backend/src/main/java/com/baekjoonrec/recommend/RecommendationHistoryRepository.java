package com.baekjoonrec.recommend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RecommendationHistoryRepository extends JpaRepository<RecommendationHistory, Long> {
    List<RecommendationHistory> findByUserIdAndStatus(Long userId, String status);
    List<RecommendationHistory> findByUserIdOrderByRecommendedAtDesc(Long userId);
    Optional<RecommendationHistory> findByUserIdAndProblemIdAndStatus(Long userId, Integer problemId, String status);
    List<RecommendationHistory> findByUserIdAndStatusOrderByRecommendedAtDesc(Long userId, String status);
    long countByUserIdAndProblemIdAndStatus(Long userId, Integer problemId, String status);
}
