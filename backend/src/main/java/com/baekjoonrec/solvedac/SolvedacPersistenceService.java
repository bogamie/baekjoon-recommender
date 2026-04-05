package com.baekjoonrec.solvedac;

import com.baekjoonrec.problem.*;
import com.baekjoonrec.solvedac.dto.SolvedacHistoryEntry;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacProblemItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagDisplay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolvedacPersistenceService {

    private final ProblemRepository problemRepository;
    private final ProblemTagRepository problemTagRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final com.baekjoonrec.recommend.RecommendationService recommendationService;

    @Transactional
    public void saveFetchedData(Long userId, List<SolvedacProblemItem> items,
                                List<SolvedacHistoryEntry> history) {
        List<Integer> solvedProblemIds = new ArrayList<>();
        for (SolvedacProblemItem item : items) {
            solvedProblemIds.add(item.getProblemId());
            saveProblemFromSearchItem(item);
        }

        Set<Integer> existingProblemIds = new HashSet<>(userSolvedRepository.findProblemIdsByUserId(userId));
        List<Integer> newProblemIds = new ArrayList<>();
        for (Integer problemId : solvedProblemIds) {
            if (!existingProblemIds.contains(problemId)) {
                newProblemIds.add(problemId);
            }
        }

        if (newProblemIds.isEmpty()) {
            return;
        }

        List<LocalDateTime> timestamps = buildTimestampList(newProblemIds.size(),
                existingProblemIds.size(), history);

        List<UserSolved> newSolvedRecords = new ArrayList<>();
        for (int i = 0; i < newProblemIds.size(); i++) {
            newSolvedRecords.add(UserSolved.builder()
                    .userId(userId)
                    .problemId(newProblemIds.get(i))
                    .solvedAt(timestamps.get(i))
                    .build());
        }

        userSolvedRepository.saveAll(newSolvedRecords);
        recommendationService.invalidateRecommendations(userId);
    }

    /**
     * Build a list of timestamps for new problems using history data.
     *
     * History entries contain cumulative solvedCount at each timestamp.
     * For a first sync (existingCount=0), we distribute all timestamps from history.
     * For incremental syncs (existingCount>0), new problems were solved recently,
     * so we assign timestamps from the most recent history entries.
     * Falls back to LocalDateTime.now() if history is unavailable.
     */
    private List<LocalDateTime> buildTimestampList(int newCount, int existingCount,
                                                    List<SolvedacHistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return Collections.nCopies(newCount, LocalDateTime.now());
        }

        // Sort history ascending by timestamp
        List<SolvedacHistoryEntry> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparing(SolvedacHistoryEntry::getTimestamp));

        // Build per-entry timestamp list: for each history entry, how many problems were solved
        // Entry value = cumulative count at that point
        List<LocalDateTime> allTimestamps = new ArrayList<>();

        // Problems before the first history entry get the first entry's timestamp
        int prevCount = 0;
        for (SolvedacHistoryEntry entry : sorted) {
            LocalDateTime ts = parseTimestamp(entry.getTimestamp());
            int count = entry.getValue() - prevCount;
            for (int j = 0; j < count; j++) {
                allTimestamps.add(ts);
            }
            prevCount = entry.getValue();
        }

        // For first sync: distribute from the full history
        // allTimestamps[i] = the approximate solvedAt for the (i+1)-th problem solved
        if (existingCount == 0) {
            // We have timestamps for allTimestamps.size() problems, need newCount
            if (allTimestamps.size() >= newCount) {
                return allTimestamps.subList(0, newCount);
            }
            // History covers fewer problems than we have; pad remaining with the last timestamp
            List<LocalDateTime> result = new ArrayList<>(allTimestamps);
            LocalDateTime last = allTimestamps.isEmpty()
                    ? LocalDateTime.now()
                    : allTimestamps.get(allTimestamps.size() - 1);
            while (result.size() < newCount) {
                result.add(last);
            }
            return result;
        }

        // Incremental sync: new problems correspond to the tail of the history
        // Take the last newCount timestamps
        if (allTimestamps.size() >= existingCount + newCount) {
            return allTimestamps.subList(existingCount, existingCount + newCount);
        }

        // Fallback: assign now (incremental syncs are usually small and recent)
        return Collections.nCopies(newCount, LocalDateTime.now());
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        try {
            return OffsetDateTime.parse(timestamp).toLocalDateTime();
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}", timestamp);
            return LocalDateTime.now();
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
