package com.baekjoonrec.solvedac;

import com.baekjoonrec.analysis.AnalysisService;
import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
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
    private final SolvedacPersistenceService persistenceService;
    private final AnalysisService analysisService;

    @Transactional
    public void resetSyncTime(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        user.setLastSyncedAt(null);
        userRepository.save(user);
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

        // Phase 2: Save to DB in a transaction (via separate bean for proxy)
        persistenceService.saveFetchedData(userId, fetchedItems);

        // Phase 3: Update last sync time
        user.setLastSyncedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Sync complete for user {}: {} problems. Running analysis...", userId, fetchedItems.size());
        analysisService.analyzeUser(userId);
    }
}
