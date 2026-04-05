package com.baekjoonrec.analysis;

import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.problem.*;
import com.baekjoonrec.solvedac.SolvedacClient;
import com.baekjoonrec.solvedac.dto.SolvedacUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int BLOCK_GAP_THRESHOLD = 7;

    private record ActivityBlockResult(int previousGapDays, int activeDaysInBlock, LocalDate blockStartDate) {}

    private final UserRepository userRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final ProblemRepository problemRepository;
    private final ProblemTagRepository problemTagRepository;
    private final UserAnalysisRepository userAnalysisRepository;
    private final UserTagStatRepository userTagStatRepository;
    private final SolvedacClient solvedacClient;

    /**
     * Full analysis with tier/rating from solved.ac. Called during sync.
     */
    @Transactional
    public UserAnalysis analyzeUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        Integer tier = null;
        Integer rating = null;
        if (user.getSolvedacHandle() != null && !user.getSolvedacHandle().isBlank()) {
            try {
                SolvedacUserResponse sacUser = solvedacClient.getUser(user.getSolvedacHandle());
                tier = sacUser.getTier();
                rating = sacUser.getRating();
            } catch (Exception e) {
                log.warn("Failed to fetch solved.ac user info for {}: {}", user.getSolvedacHandle(), e.getMessage());
                // Fall back to cached values
                UserAnalysis cached = userAnalysisRepository.findByUserId(userId).orElse(null);
                if (cached != null) {
                    tier = cached.getTier();
                    rating = cached.getRating();
                }
            }
        }

        return analyzeUserInternal(userId, tier, rating);
    }

    /**
     * Internal analysis logic, no external API calls.
     */
    private UserAnalysis analyzeUserInternal(Long userId, Integer tier, Integer rating) {
        List<UserSolved> solvedList = userSolvedRepository.findByUserId(userId);
        int totalSolved = solvedList.size();

        String globalStatus;
        int gapDays = 0;
        int activeDays90 = 0;
        int previousGapDays = 0;
        int activeDaysInBlock = 0;
        LocalDate blockStartDate = null;

        if (totalSolved == 0) {
            globalStatus = "NEWCOMER";
        } else {
            List<LocalDate> solvedDates = solvedList.stream()
                    .map(us -> us.getSolvedAt() != null ? us.getSolvedAt().toLocalDate() : LocalDate.now())
                    .sorted(Comparator.reverseOrder())
                    .distinct()
                    .collect(Collectors.toList());

            LocalDate now = LocalDate.now();
            gapDays = (int) ChronoUnit.DAYS.between(solvedDates.get(0), now);

            LocalDate ninetyDaysAgo = now.minusDays(90);
            activeDays90 = (int) solvedDates.stream()
                    .filter(d -> !d.isBefore(ninetyDaysAgo))
                    .count();

            ActivityBlockResult block = detectActivityBlock(solvedDates);
            previousGapDays = block.previousGapDays();
            activeDaysInBlock = block.activeDaysInBlock();
            blockStartDate = block.blockStartDate();

            if (gapDays >= 30) {
                globalStatus = "RETURNING_EARLY";
            } else if (previousGapDays >= 30) {
                int threshold = computeRecoveryThreshold(previousGapDays);
                if (activeDaysInBlock >= threshold) {
                    globalStatus = "ACTIVE";
                } else if (activeDaysInBlock >= (threshold + 1) / 2) {
                    globalStatus = "RETURNING_MID";
                } else {
                    globalStatus = "RETURNING_EARLY";
                }
            } else {
                globalStatus = "ACTIVE";
            }
        }

        // Save user_analysis
        UserAnalysis analysis = userAnalysisRepository.findById(userId)
                .orElse(new UserAnalysis());
        analysis.setUserId(userId);
        analysis.setGlobalStatus(globalStatus);
        analysis.setGapDays(gapDays);
        analysis.setActiveDays90(activeDays90);
        analysis.setPreviousGapDays(previousGapDays);
        analysis.setActiveDaysInBlock(activeDaysInBlock);
        analysis.setReturnedAt(previousGapDays >= 30 && blockStartDate != null
                ? blockStartDate.atStartOfDay() : null);
        analysis.setTotalSolved(totalSolved);
        analysis.setTier(tier);
        analysis.setRating(rating);
        userAnalysisRepository.save(analysis);

        // Per-tag proficiency with enhanced matrix
        analyzeTagStats(userId, solvedList, blockStartDate, previousGapDays, tier);

        return analysis;
    }

    private ActivityBlockResult detectActivityBlock(List<LocalDate> solvedDates) {
        int blockStartIndex = 0;
        for (int i = 1; i < solvedDates.size(); i++) {
            long daysBetween = ChronoUnit.DAYS.between(solvedDates.get(i), solvedDates.get(i - 1));
            if (daysBetween > BLOCK_GAP_THRESHOLD) {
                break;
            }
            blockStartIndex = i;
        }

        LocalDate blockStartDate = solvedDates.get(blockStartIndex);
        int activeDaysInBlock = blockStartIndex + 1;

        int previousGapDays = 0;
        if (blockStartIndex + 1 < solvedDates.size()) {
            previousGapDays = (int) ChronoUnit.DAYS.between(
                    solvedDates.get(blockStartIndex + 1), blockStartDate);
        }

        return new ActivityBlockResult(previousGapDays, activeDaysInBlock, blockStartDate);
    }

    private int computeRecoveryThreshold(int previousGapDays) {
        if (previousGapDays >= 180) return 21;
        if (previousGapDays >= 90) return 14;
        return 7;
    }

    private void analyzeTagStats(Long userId, List<UserSolved> solvedList,
                                  LocalDate blockStartDate, int previousGapDays, Integer userTier) {
        userTagStatRepository.deleteByUserId(userId);

        if (solvedList.isEmpty()) return;

        // Batch fetch all problem IDs' tags and problems in one query each
        List<Integer> allProblemIds = solvedList.stream()
                .map(UserSolved::getProblemId)
                .collect(Collectors.toList());

        List<ProblemTag> allTags = problemTagRepository.findByProblemIdIn(allProblemIds);
        Map<Integer, List<ProblemTag>> tagsByProblemId = allTags.stream()
                .collect(Collectors.groupingBy(ProblemTag::getProblemId));

        List<Problem> allProblems = problemRepository.findAllById(allProblemIds);
        Map<Integer, Integer> problemLevelMap = allProblems.stream()
                .collect(Collectors.toMap(Problem::getId, Problem::getLevel, (a, b) -> a));

        // Group problems by tag
        Map<String, List<UserSolved>> tagToSolved = new HashMap<>();
        Map<String, String> tagKeyToName = new HashMap<>();

        for (UserSolved us : solvedList) {
            List<ProblemTag> tags = tagsByProblemId.getOrDefault(us.getProblemId(), List.of());
            for (ProblemTag tag : tags) {
                tagToSolved.computeIfAbsent(tag.getTagKey(), k -> new ArrayList<>()).add(us);
                tagKeyToName.putIfAbsent(tag.getTagKey(), tag.getTagName());
            }
        }

        int effectiveTier = userTier != null ? userTier : 1;
        List<UserTagStat> statsToSave = new ArrayList<>();

        for (Map.Entry<String, List<UserSolved>> entry : tagToSolved.entrySet()) {
            String tagKey = entry.getKey();
            List<UserSolved> tagSolved = entry.getValue();
            int count = tagSolved.size();

            // Compute avg and max level using pre-fetched map
            List<Integer> levels = tagSolved.stream()
                    .map(us -> problemLevelMap.getOrDefault(us.getProblemId(), 0))
                    .filter(l -> l > 0)
                    .collect(Collectors.toList());

            double avgLevel = levels.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            int maxLevel = levels.stream().mapToInt(Integer::intValue).max().orElse(0);

            // Effective problem pool: problems in [Bronze5(1) .. userTier+2] for this tag
            int poolUpperBound = Math.min(effectiveTier + 2, 30);
            long effectivePoolSize = problemRepository.countByTagAndLevelRange(tagKey, 1, poolUpperBound);
            if (effectivePoolSize == 0) effectivePoolSize = 1;

            // Near-avg count: problems solved in [avg-1, avg+1] range
            int avgInt = (int) Math.round(avgLevel);
            int nearAvgCount = (int) levels.stream()
                    .filter(l -> l >= avgInt - 1 && l <= avgInt + 1)
                    .count();

            String proficiency = calculateProficiency(count, effectivePoolSize, maxLevel, effectiveTier);

            LocalDateTime lastSolvedAt = tagSolved.stream()
                    .map(UserSolved::getSolvedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            boolean isDormant = previousGapDays >= 30
                    && blockStartDate != null
                    && lastSolvedAt != null
                    && lastSolvedAt.toLocalDate().isBefore(blockStartDate);

            statsToSave.add(UserTagStat.builder()
                    .userId(userId)
                    .tagKey(tagKey)
                    .tagName(tagKeyToName.get(tagKey))
                    .solvedCount(count)
                    .avgLevel(avgLevel)
                    .maxLevel(maxLevel)
                    .lastSolvedAt(lastSolvedAt)
                    .proficiency(proficiency)
                    .isDormant(isDormant)
                    .effectivePoolSize((int) effectivePoolSize)
                    .nearAvgCount(nearAvgCount)
                    .build());
        }

        userTagStatRepository.saveAll(statsToSave);
    }

    /**
     * Enhanced proficiency using ratio + difficulty reach matrix.
     *
     * Solve ratio grades:   LOW (<10%), MEDIUM (10-25%), HIGH (>25%)
     * Difficulty reach:     LOW (<0.6), MEDIUM (0.6-0.9), HIGH (>0.9)
     *
     * Matrix:
     *              reach LOW    reach MED    reach HIGH
     * ratio LOW    BEGINNER     BEGINNER     INTERMEDIATE
     * ratio MED    BEGINNER     INTERMEDIATE ADVANCED
     * ratio HIGH   INTERMEDIATE ADVANCED     ADVANCED
     */
    private String calculateProficiency(int solvedCount, long effectivePoolSize, int maxLevel, int userTier) {
        if (solvedCount == 0) return "UNEXPLORED";

        double solveRatio = (double) solvedCount / effectivePoolSize;
        double difficultyReach = userTier > 0 ? (double) maxLevel / userTier : 0.0;

        // Solve ratio grade
        int ratioGrade; // 0=LOW, 1=MEDIUM, 2=HIGH
        if (solveRatio < 0.10) ratioGrade = 0;
        else if (solveRatio <= 0.25) ratioGrade = 1;
        else ratioGrade = 2;

        // Difficulty reach grade
        int reachGrade; // 0=LOW, 1=MEDIUM, 2=HIGH
        if (difficultyReach < 0.6) reachGrade = 0;
        else if (difficultyReach <= 0.9) reachGrade = 1;
        else reachGrade = 2;

        // Matrix lookup
        String[][] matrix = {
                {"BEGINNER", "BEGINNER", "INTERMEDIATE"},
                {"BEGINNER", "INTERMEDIATE", "ADVANCED"},
                {"INTERMEDIATE", "ADVANCED", "ADVANCED"}
        };

        return matrix[ratioGrade][reachGrade];
    }

    public UserAnalysis getUserAnalysis(Long userId) {
        return userAnalysisRepository.findByUserId(userId).orElse(null);
    }

    public List<UserTagStat> getUserTagStats(Long userId) {
        return userTagStatRepository.findByUserId(userId);
    }
}
