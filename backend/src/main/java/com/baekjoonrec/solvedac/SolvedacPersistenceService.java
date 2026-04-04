package com.baekjoonrec.solvedac;

import com.baekjoonrec.problem.*;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacProblemItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagDisplay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SolvedacPersistenceService {

    private final ProblemRepository problemRepository;
    private final ProblemTagRepository problemTagRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final com.baekjoonrec.recommend.RecommendationService recommendationService;

    @Transactional
    public void saveFetchedData(Long userId, List<SolvedacProblemItem> items) {
        List<Integer> solvedProblemIds = new ArrayList<>();
        for (SolvedacProblemItem item : items) {
            solvedProblemIds.add(item.getProblemId());
            saveProblemFromSearchItem(item);
        }

        Set<Integer> existingProblemIds = new HashSet<>(userSolvedRepository.findProblemIdsByUserId(userId));
        List<UserSolved> newSolvedRecords = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Integer problemId : solvedProblemIds) {
            if (!existingProblemIds.contains(problemId)) {
                newSolvedRecords.add(UserSolved.builder()
                        .userId(userId)
                        .problemId(problemId)
                        .solvedAt(now)
                        .build());
            }
        }
        if (!newSolvedRecords.isEmpty()) {
            userSolvedRepository.saveAll(newSolvedRecords);
            recommendationService.invalidateRecommendations(userId);
        }
    }

    private void saveProblemFromSearchItem(SolvedacProblemItem item) {
        if (problemRepository.existsById(item.getProblemId())) {
            return;
        }

        Problem problem = Problem.builder()
                .id(item.getProblemId())
                .title(item.getTitleKo())
                .level(item.getLevel())
                .solvedCount(item.getAcceptedUserCount())
                .acceptedRate(item.getAverageTries() > 0 ? 1.0 / item.getAverageTries() : 0.0)
                .build();
        problemRepository.save(problem);

        if (item.getTags() != null) {
            for (SolvedacTagItem tag : item.getTags()) {
                String tagName = tag.getDisplayNames() != null && !tag.getDisplayNames().isEmpty()
                        ? tag.getDisplayNames().stream()
                            .filter(d -> "ko".equals(d.getLanguage()))
                            .findFirst()
                            .map(SolvedacTagDisplay::getName)
                            .orElse(tag.getDisplayNames().get(0).getName())
                        : tag.getKey();

                ProblemTag pt = ProblemTag.builder()
                        .problemId(item.getProblemId())
                        .tagKey(tag.getKey())
                        .tagName(tagName)
                        .build();
                problemTagRepository.save(pt);
            }
        }
    }
}
