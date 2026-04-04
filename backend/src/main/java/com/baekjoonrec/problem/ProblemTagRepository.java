package com.baekjoonrec.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProblemTagRepository extends JpaRepository<ProblemTag, ProblemTagId> {
    List<ProblemTag> findByProblemId(Integer problemId);
    List<ProblemTag> findByProblemIdIn(List<Integer> problemIds);
}
