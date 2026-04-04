package com.baekjoonrec.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProblemRepository extends JpaRepository<Problem, Integer> {

    @Query("SELECT p FROM Problem p JOIN ProblemTag pt ON p.id = pt.problemId " +
           "WHERE pt.tagKey = :tagKey AND p.level BETWEEN :minLevel AND :maxLevel " +
           "AND p.id NOT IN :excludeIds " +
           "AND (p.solvedCount IS NULL OR p.solvedCount >= 50) " +
           "AND (p.acceptedRate IS NULL OR p.acceptedRate >= 0.05) " +
           "ORDER BY p.level ASC")
    List<Problem> findByTagAndLevelRange(
            @Param("tagKey") String tagKey,
            @Param("minLevel") int minLevel,
            @Param("maxLevel") int maxLevel,
            @Param("excludeIds") List<Integer> excludeIds);

    @Query("SELECT p FROM Problem p JOIN ProblemTag pt1 ON p.id = pt1.problemId " +
           "JOIN ProblemTag pt2 ON p.id = pt2.problemId " +
           "WHERE pt1.tagKey = :tag1 AND pt2.tagKey = :tag2 " +
           "AND p.level BETWEEN :minLevel AND :maxLevel " +
           "AND p.id NOT IN :excludeIds " +
           "AND (p.solvedCount IS NULL OR p.solvedCount >= 50) " +
           "AND (p.acceptedRate IS NULL OR p.acceptedRate >= 0.05) " +
           "ORDER BY p.level ASC")
    List<Problem> findByTwoTagsAndLevelRange(
            @Param("tag1") String tag1,
            @Param("tag2") String tag2,
            @Param("minLevel") int minLevel,
            @Param("maxLevel") int maxLevel,
            @Param("excludeIds") List<Integer> excludeIds);

    @Query("SELECT COUNT(p) FROM Problem p JOIN ProblemTag pt ON p.id = pt.problemId " +
           "WHERE pt.tagKey = :tagKey AND p.level BETWEEN :minLevel AND :maxLevel")
    long countByTagAndLevelRange(
            @Param("tagKey") String tagKey,
            @Param("minLevel") int minLevel,
            @Param("maxLevel") int maxLevel);
}

