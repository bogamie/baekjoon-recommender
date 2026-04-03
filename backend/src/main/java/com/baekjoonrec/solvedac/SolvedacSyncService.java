package com.baekjoonrec.solvedac;

import com.baekjoonrec.analysis.AnalysisService;
import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.problem.*;
import com.baekjoonrec.solvedac.dto.SolvedacProblemResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacProblemItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagItem;
import com.baekjoonrec.solvedac.dto.SolvedacUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolvedacSyncService {

    private final SolvedacClient solvedacClient;
    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final ProblemTagRepository problemTagRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final AnalysisService analysisService;
    private final com.baekjoonrec.recommend.RecommendationService recommendationService;

    private final Map<Long, LocalDateTime> lastSyncTimeByUser = new ConcurrentHashMap<>();

    public void resetSyncTime(Long userId) {
        lastSyncTimeByUser.remove(userId);
    }

    public SolvedacUserResponse getUserInfo(String handle) {
        return solvedacClient.getUser(handle);
    }

    @Transactional
    public void syncUserSolvedProblems(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        if (user.getSolvedacHandle() == null || user.getSolvedacHandle().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_HANDLE", "solved.ac handle not set");
        }

        // Skip if synced within 24 hours
        LocalDateTime lastSync = lastSyncTimeByUser.get(userId);
        if (lastSync != null && lastSync.isAfter(LocalDateTime.now().minusHours(24))) {
            log.info("Skipping sync for user {}, last sync was recent", userId);
            return;
        }

        String handle = user.getSolvedacHandle();
        log.info("Starting sync for user {} with handle {}", userId, handle);

        // Fetch all solved problem IDs
        List<Integer> solvedProblemIds = new ArrayList<>();
        int page = 1;
        while (true) {
            SolvedacSearchResponse response = solvedacClient.searchSolvedProblems(handle, page);
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }
            for (SolvedacProblemItem item : response.getItems()) {
                solvedProblemIds.add(item.getProblemId());
                saveProblemFromSearchItem(item);
            }
            if (solvedProblemIds.size() >= response.getCount()) {
                break;
            }
            page++;
        }

        // Batch save user_solved records — fetch existing IDs first to avoid N queries
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
        }

        lastSyncTimeByUser.put(userId, LocalDateTime.now());
        log.info("Sync complete for user {}: {} problems. Running analysis...", userId, solvedProblemIds.size());
        analysisService.analyzeUser(userId);

        // Invalidate cached recommendations if user solved new problems
        if (!newSolvedRecords.isEmpty()) {
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
                            .map(SolvedacSearchResponse.SolvedacTagDisplay::getName)
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
