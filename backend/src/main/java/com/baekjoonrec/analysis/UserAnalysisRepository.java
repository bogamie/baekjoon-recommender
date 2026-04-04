package com.baekjoonrec.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserAnalysisRepository extends JpaRepository<UserAnalysis, Long> {
    Optional<UserAnalysis> findByUserId(Long userId);
}
