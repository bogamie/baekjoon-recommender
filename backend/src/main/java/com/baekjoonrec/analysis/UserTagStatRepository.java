package com.baekjoonrec.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserTagStatRepository extends JpaRepository<UserTagStat, UserTagStatId> {
    List<UserTagStat> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
