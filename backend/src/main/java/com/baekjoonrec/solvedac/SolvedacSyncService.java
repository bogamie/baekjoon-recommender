package com.baekjoonrec.solvedac;

import com.baekjoonrec.analysis.AnalysisService;
import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.solvedac.dto.SolvedacHistoryEntry;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacProblemItem;
import com.baekjoonrec.solvedac.dto.SolvedacUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolvedacSyncService {

    private final SolvedacClient solvedacClient;
    private final UserRepository userRepository;
    private final com.baekjoonrec.problem.UserSolvedRepository userSolvedRepository;
    private final SolvedacPersistenceService persistenceService;
    private final AnalysisService analysisService;

    @Transactional
    public void resetSyncTime(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        user.setLastSyncedAt(null);
        userRepository.save(user);
    }

    /**
     * Full resync: deletes existing UserSolved records and re-fetches with accurate timestamps.
     * Use this to fix users whose solvedAt was incorrectly set to sync time.
     */
    @Transactional
    public void fullResync(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        if (user.getSolvedacHandle() == null || user.getSolvedacHandle().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_HANDLE", "solved.ac handle not set");
        }

        log.info("Starting full resync for user {} — deleting existing records", userId);
        userSolvedRepository.deleteByUserId(userId);

        user.setLastSyncedAt(null);
        userRepository.save(user);

        syncUserSolvedProblems(userId);
    }

    public SolvedacUserResponse getUserInfo(String handle) {
        return solvedacClient.getUser(handle);
    }

    public void syncUserSolvedProblems(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        if (user.getSolvedacHandle() == null || user.getSolvedacHandle().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_HANDLE", "solved.ac handle not set");
        }

        // Skip if synced within 24 hours
        LocalDateTime lastSync = user.getLastSyncedAt();
        if (lastSync != null && lastSync.isAfter(LocalDateTime.now().minusHours(24))) {
            log.info("Skipping sync for user {}, last sync was recent", userId);
            return;
        }

        String handle = user.getSolvedacHandle();
        log.info("Starting sync for user {} with handle {}", userId, handle);

        // Detect corrupted timestamps (all same value) and auto-trigger full resync
        if (userSolvedRepository.existsByUserId(userId) && hasCorruptedTimestamps(userId)) {
            log.info("Detected corrupted timestamps for user {}, triggering full resync", userId);
            userSolvedRepository.deleteByUserId(userId);
            user.setLastSyncedAt(null);
            userRepository.save(user);
        }

        // Phase 1: Fetch data from solved.ac API (no transaction — no DB connection held)
        List<SolvedacProblemItem> fetchedItems = new ArrayList<>();
        int page = 1;
        while (true) {
            SolvedacSearchResponse response = solvedacClient.searchSolvedProblems(handle, page);
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }
            fetchedItems.addAll(response.getItems());
            if (fetchedItems.size() >= response.getCount()) {
                break;
            }
            page++;
        }

        // Phase 1.5: Fetch solve history for accurate timestamps
        List<SolvedacHistoryEntry> history = List.of();
        try {
            history = solvedacClient.getUserHistory(handle);
        } catch (Exception e) {
            log.warn("Failed to fetch history for {}", handle, e);
            // First sync requires history for accurate timestamps; without it, analysis is meaningless
            if (!userSolvedRepository.existsByUserId(userId)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HISTORY_UNAVAILABLE",
                        "solved.ac 히스토리를 가져올 수 없습니다. 잠시 후 다시 시도해주세요.");
            }
            // Incremental sync: few recent problems, now() is acceptable
        }

        // Phase 2: Save to DB in a transaction (via separate bean for proxy)
        persistenceService.saveFetchedData(userId, fetchedItems, history);

        // Phase 3: Update last sync time
        user.setLastSyncedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Sync complete for user {}: {} problems. Running analysis...", userId, fetchedItems.size());
        analysisService.analyzeUser(userId);
    }

    /**
     * Detects if a user's solved timestamps are corrupted (most share the same date),
     * which happens when history fetch failed or was incomplete during first sync.
     */
    private boolean hasCorruptedTimestamps(Long userId) {
        long distinctDates = userSolvedRepository.countDistinctSolvedAtByUserId(userId);
        long totalCount = userSolvedRepository.countByUserId(userId);
        // If 10+ problems exist but distinct dates cover less than 5% of total, timestamps are corrupted
        // Normal users solve problems across many different dates
        return totalCount >= 10 && distinctDates <= Math.max(1, totalCount / 20);
    }
}
