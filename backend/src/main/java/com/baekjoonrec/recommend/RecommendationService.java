package com.baekjoonrec.recommend;

import com.baekjoonrec.analysis.AnalysisService;
import com.baekjoonrec.analysis.UserAnalysis;
import com.baekjoonrec.analysis.UserTagStat;
import com.baekjoonrec.auth.User;
import com.baekjoonrec.auth.UserRepository;
import com.baekjoonrec.common.ApiException;
import com.baekjoonrec.problem.*;
import com.baekjoonrec.recommend.dto.RecommendationResponse;
import com.baekjoonrec.recommend.dto.RecommendationResponse.*;
import com.baekjoonrec.solvedac.SolvedacClient;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacProblemItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagItem;
import com.baekjoonrec.solvedac.dto.SolvedacSearchResponse.SolvedacTagDisplay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final java.util.regex.Pattern KOREAN_PATTERN = java.util.regex.Pattern.compile("[가-힣]");

    private final AnalysisService analysisService;
    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final ProblemTagRepository problemTagRepository;
    private final UserSolvedRepository userSolvedRepository;
    private final RecommendationHistoryRepository historyRepository;
    private final SolvedacClient solvedacClient;

    private static final String[] TIER_NAMES = {
            "Unrated",
            "Bronze V", "Bronze IV", "Bronze III", "Bronze II", "Bronze I",
            "Silver V", "Silver IV", "Silver III", "Silver II", "Silver I",
            "Gold V", "Gold IV", "Gold III", "Gold II", "Gold I",
            "Platinum V", "Platinum IV", "Platinum III", "Platinum II", "Platinum I",
            "Diamond V", "Diamond IV", "Diamond III", "Diamond II", "Diamond I",
            "Ruby V", "Ruby IV", "Ruby III", "Ruby II", "Ruby I"
    };

    /**
     * Returns existing PENDING recommendations if available, otherwise generates new ones.
     */
    @Transactional
    public RecommendationResponse getRecommendations(Long userId) {
        List<RecommendationHistory> pending = historyRepository.findByUserIdAndStatus(userId, "PENDING");
        if (!pending.isEmpty()) {
            return buildResponseFromHistory(userId, pending);
        }
        return generateRecommendations(userId);
    }

    /**
     * Always generates fresh recommendations (used by refresh and after sync invalidation).
     */
    @Transactional
    public RecommendationResponse generateRecommendations(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        boolean includeForeign = user == null || user.getIncludeForeign() == null || user.getIncludeForeign();

        UserAnalysis analysis = analysisService.getUserAnalysis(userId);
        if (analysis == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_ANALYSIS", "분석 데이터가 없습니다. 먼저 동기화해주세요.");
        }

        List<UserTagStat> tagStats = analysisService.getUserTagStats(userId);
        if (tagStats.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_TAG_STATS", "태그 데이터가 없습니다. 먼저 동기화해주세요.");
        }

        int userTier = analysis.getTier() != null ? analysis.getTier() : 1;
        String globalStatus = analysis.getGlobalStatus();

        // Get already-solved problem IDs
        Set<Integer> solvedIds = userSolvedRepository.findByUserId(userId).stream()
                .map(UserSolved::getProblemId)
                .collect(Collectors.toSet());

        // Get previously skipped problem IDs
        Set<Integer> skippedIds = historyRepository.findByUserIdAndStatus(userId, "SKIPPED").stream()
                .map(RecommendationHistory::getProblemId)
                .collect(Collectors.toSet());

        // Invalidate old pending recommendations
        List<RecommendationHistory> oldPending = historyRepository.findByUserIdAndStatus(userId, "PENDING");
        for (RecommendationHistory h : oldPending) {
            if (solvedIds.contains(h.getProblemId())) {
                h.setStatus("SOLVED");
            } else {
                h.setStatus("REPLACED");
            }
            historyRepository.save(h);
        }

        // Compute category priorities
        List<CategoryScore> scores = computeCategoryPriorities(tagStats, globalStatus);

        // Build exclude set: solved + skipped (skipped get lower priority, not hard exclude)
        Set<Integer> hardExclude = new HashSet<>(solvedIds);

        // Allocate slots based on user status
        List<RecommendationSlot> slots;
        if ("RETURNING_EARLY".equals(globalStatus) || "RETURNING_MID".equals(globalStatus)) {
            slots = allocateReturningSlots(scores, tagStats, userTier, hardExclude, skippedIds, includeForeign);
        } else {
            slots = allocateActiveSlots(scores, tagStats, userTier, hardExclude, skippedIds, includeForeign);
        }

        // Save recommendation history
        for (RecommendationSlot slot : slots) {
            RecommendationHistory history = RecommendationHistory.builder()
                    .userId(userId)
                    .problemId(slot.getProblem().getId())
                    .slotType(slot.getSlotType())
                    .targetTag(slot.getReason().getCategory())
                    .build();
            historyRepository.save(history);
        }

        // Build user profile summary
        long advancedCount = tagStats.stream().filter(s -> "ADVANCED".equals(s.getProficiency())).count();
        long intermediateCount = tagStats.stream().filter(s -> "INTERMEDIATE".equals(s.getProficiency())).count();
        long beginnerCount = tagStats.stream().filter(s -> "BEGINNER".equals(s.getProficiency())).count();

        UserProfileSummary profile = UserProfileSummary.builder()
                .tier(tierName(userTier))
                .totalSolved(analysis.getTotalSolved())
                .advancedCount((int) advancedCount)
                .intermediateCount((int) intermediateCount)
                .beginnerCount((int) beginnerCount)
                .build();

        return RecommendationResponse.builder()
                .recommendations(slots)
                .userProfile(profile)
                .build();
    }

    /**
     * Invalidates PENDING recommendations for a user (called after sync detects new solves).
     */
    @Transactional
    public void invalidateRecommendations(Long userId) {
        List<RecommendationHistory> pending = historyRepository.findByUserIdAndStatus(userId, "PENDING");
        if (pending.isEmpty()) return;

        Set<Integer> solvedIds = userSolvedRepository.findByUserId(userId).stream()
                .map(UserSolved::getProblemId)
                .collect(Collectors.toSet());

        for (RecommendationHistory h : pending) {
            h.setStatus(solvedIds.contains(h.getProblemId()) ? "SOLVED" : "REPLACED");
            historyRepository.save(h);
        }
        log.info("Invalidated {} pending recommendations for user {}", pending.size(), userId);
    }

    /**
     * Builds a RecommendationResponse from existing PENDING history records.
     */
    private RecommendationResponse buildResponseFromHistory(Long userId, List<RecommendationHistory> pending) {
        UserAnalysis analysis = analysisService.getUserAnalysis(userId);
        List<UserTagStat> tagStats = analysisService.getUserTagStats(userId);

        List<RecommendationSlot> slots = new ArrayList<>();
        for (RecommendationHistory h : pending) {
            Problem problem = problemRepository.findById(h.getProblemId()).orElse(null);
            if (problem == null) continue;

            List<String> problemTags = problemTagRepository.findByProblemId(problem.getId()).stream()
                    .map(ProblemTag::getTagKey)
                    .collect(Collectors.toList());

            // Find matching tag stat for reason info
            String targetTag = h.getTargetTag();
            String categoryName = targetTag;
            String proficiency = "UNKNOWN";
            String message = "추천 문제";

            // Handle multi-tag (e.g. "dp+graphs")
            if (targetTag != null && !targetTag.contains("+")) {
                UserTagStat matchingStat = tagStats.stream()
                        .filter(s -> targetTag.equals(s.getTagKey()))
                        .findFirst().orElse(null);
                if (matchingStat != null) {
                    categoryName = matchingStat.getTagName() != null ? matchingStat.getTagName() : targetTag;
                    proficiency = matchingStat.getProficiency();
                }
            }

            String slotType = h.getSlotType();
            message = switch (slotType) {
                case "REVIEW" -> categoryName + " 카테고리 복습";
                case "GROWTH" -> categoryName + " 카테고리 성장을 위한 문제";
                case "CHALLENGE" -> categoryName + " 고난이도 도전";
                case "ENTRY" -> categoryName + " 카테고리 기초 다지기";
                case "EXPLORE" -> "새로운 카테고리 도전";
                default -> categoryName + " 추천 문제";
            };

            slots.add(RecommendationSlot.builder()
                    .slotType(slotType)
                    .problem(ProblemInfo.builder()
                            .id(problem.getId())
                            .title(problem.getTitle())
                            .level(problem.getLevel())
                            .levelName(tierName(problem.getLevel()))
                            .tags(problemTags)
                            .solvedCount(problem.getSolvedCount())
                            .acceptanceRate(problem.getAcceptedRate())
                            .build())
                    .reason(ReasonInfo.builder()
                            .category(targetTag)
                            .categoryName(categoryName)
                            .proficiency(proficiency)
                            .message(message)
                            .build())
                    .build());
        }

        int userTier = analysis != null && analysis.getTier() != null ? analysis.getTier() : 1;
        long advancedCount = tagStats.stream().filter(s -> "ADVANCED".equals(s.getProficiency())).count();
        long intermediateCount = tagStats.stream().filter(s -> "INTERMEDIATE".equals(s.getProficiency())).count();
        long beginnerCount = tagStats.stream().filter(s -> "BEGINNER".equals(s.getProficiency())).count();

        UserProfileSummary profile = UserProfileSummary.builder()
                .tier(tierName(userTier))
                .totalSolved(analysis != null ? analysis.getTotalSolved() : 0)
                .advancedCount((int) advancedCount)
                .intermediateCount((int) intermediateCount)
                .beginnerCount((int) beginnerCount)
                .build();

        return RecommendationResponse.builder()
                .recommendations(slots)
                .userProfile(profile)
                .build();
    }

    @Transactional
    public void skipProblem(Long userId, Integer problemId) {
        Optional<RecommendationHistory> opt = historyRepository
                .findByUserIdAndProblemIdAndStatus(userId, problemId, "PENDING");
        if (opt.isPresent()) {
            RecommendationHistory h = opt.get();
            h.setStatus("SKIPPED");
            h.setSkippedAt(LocalDateTime.now());
            historyRepository.save(h);
        }
    }

    /**
     * Force-regenerates recommendations (explicit user action via refresh button).
     */
    @Transactional
    public RecommendationResponse refreshRecommendations(Long userId) {
        return generateRecommendations(userId);
    }

    // ── Category Priority Scoring ──

    private List<CategoryScore> computeCategoryPriorities(List<UserTagStat> stats, String globalStatus) {
        List<CategoryScore> scores = new ArrayList<>();

        for (UserTagStat stat : stats) {
            int score = 0;

            // Base score by proficiency
            switch (stat.getProficiency()) {
                case "BEGINNER" -> score = 30;
                case "INTERMEDIATE" -> score = 20;
                case "ADVANCED" -> score = 10;
                case "UNEXPLORED" -> score = 15;
            }

            // Activity status bonus
            if (stat.getIsDormant() != null && stat.getIsDormant()) {
                if ("RETURNING_EARLY".equals(globalStatus)) score += 15;
                else if ("RETURNING_MID".equals(globalStatus)) score += 10;
            }

            // Gap bonus: max - avg > 2
            int gap = stat.getMaxLevel() - (int) Math.round(stat.getAvgLevel());
            if (gap > 2) score += 5;

            // Saturation bonus
            if (isSaturated(stat)) score += 5;

            scores.add(new CategoryScore(stat, score));
        }

        scores.sort(Comparator.comparingInt(CategoryScore::score).reversed());
        return scores;
    }

    private boolean isSaturated(UserTagStat stat) {
        int threshold = Math.min(5, (int) (stat.getSolvedCount() * 0.3));
        if (threshold < 1) threshold = 1;
        return stat.getNearAvgCount() != null && stat.getNearAvgCount() >= threshold;
    }

    // ── Adaptive Difficulty ──

    private int[] computeDifficultyRange(UserTagStat stat, int userTier) {
        int avgInt = (int) Math.round(stat.getAvgLevel());
        int maxLevel = stat.getMaxLevel();
        int gap = maxLevel - avgInt;
        boolean saturated = isSaturated(stat);

        int lower, upper;

        if (stat.getSolvedCount() < 5) {
            // Case 4: insufficient data
            lower = avgInt - 2;
            upper = avgInt + 1;
        } else if (saturated && gap <= 2) {
            // Case 1: saturated + small gap → breakthrough
            lower = maxLevel;
            upper = maxLevel + 2;
        } else if (saturated) {
            // Case 2: saturated + large gap → fill middle
            lower = avgInt + 1;
            upper = maxLevel - 1;
        } else {
            // Case 3: not saturated → stay near avg
            lower = avgInt - 1;
            upper = avgInt + 1;
        }

        // Clamp
        lower = Math.max(lower, 1);
        upper = Math.min(upper, userTier + 5);
        upper = Math.min(upper, 30);
        if (lower > upper) lower = upper;

        return new int[]{lower, upper};
    }

    // ── Slot Allocation: Active User ──

    private List<RecommendationSlot> allocateActiveSlots(
            List<CategoryScore> scores, List<UserTagStat> tagStats,
            int userTier, Set<Integer> hardExclude, Set<Integer> skippedIds, boolean includeForeign) {

        List<RecommendationSlot> slots = new ArrayList<>();
        Set<String> usedTags = new HashSet<>();
        Set<Integer> usedProblems = new HashSet<>(hardExclude);

        // Growth 2: INTERMEDIATE categories, highest priority
        List<CategoryScore> intermediates = scores.stream()
                .filter(s -> "INTERMEDIATE".equals(s.stat().getProficiency()))
                .toList();
        for (int i = 0; i < Math.min(2, intermediates.size()); i++) {
            CategoryScore cs = intermediates.get(i);
            int[] range = computeDifficultyRange(cs.stat(), userTier);
            RecommendationSlot slot = pickProblem(cs.stat(), range, "GROWTH", usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }

        // Challenge 1: ADVANCED category or multi-tag
        List<CategoryScore> advancedList = scores.stream()
                .filter(s -> "ADVANCED".equals(s.stat().getProficiency()))
                .toList();
        if (advancedList.size() >= 2) {
            // Try multi-tag recommendation
            RecommendationSlot multiSlot = pickMultiTagProblem(
                    advancedList.get(0).stat(), advancedList.get(1).stat(),
                    userTier, usedProblems, skippedIds, includeForeign);
            if (multiSlot != null) {
                slots.add(multiSlot);
                usedProblems.add(multiSlot.getProblem().getId());
            }
        }
        if (slots.size() < 3 && !advancedList.isEmpty()) {
            CategoryScore cs = advancedList.get(0);
            int[] range = new int[]{cs.stat().getMaxLevel(), Math.min(cs.stat().getMaxLevel() + 2, userTier + 5)};
            range[1] = Math.min(range[1], 30);
            RecommendationSlot slot = pickProblem(cs.stat(), range, "CHALLENGE", usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }

        // Entry 1: BEGINNER category
        List<CategoryScore> beginners = scores.stream()
                .filter(s -> "BEGINNER".equals(s.stat().getProficiency()))
                .filter(s -> !usedTags.contains(s.stat().getTagKey()))
                .toList();
        if (!beginners.isEmpty()) {
            CategoryScore cs = beginners.get(0);
            int[] range = computeDifficultyRange(cs.stat(), userTier);
            RecommendationSlot slot = pickProblem(cs.stat(), range, "ENTRY", usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }

        // Explore 1: UNEXPLORED category (find tags user hasn't solved)
        RecommendationSlot exploreSlot = pickExploreProblem(tagStats, userTier, usedProblems, usedTags, skippedIds, includeForeign);
        if (exploreSlot != null) {
            slots.add(exploreSlot);
        }

        // Fill remaining slots if we don't have 5
        fillRemainingSlots(slots, scores, userTier, usedProblems, usedTags, skippedIds, 5, includeForeign);

        return slots;
    }

    // ── Slot Allocation: Returning User ──

    private List<RecommendationSlot> allocateReturningSlots(
            List<CategoryScore> scores, List<UserTagStat> tagStats,
            int userTier, Set<Integer> hardExclude, Set<Integer> skippedIds, boolean includeForeign) {

        List<RecommendationSlot> slots = new ArrayList<>();
        Set<String> usedTags = new HashSet<>();
        Set<Integer> usedProblems = new HashSet<>(hardExclude);

        // Review 3: RETURNING (dormant) categories, easy difficulty
        List<CategoryScore> dormant = scores.stream()
                .filter(s -> s.stat().getIsDormant() != null && s.stat().getIsDormant())
                .toList();
        for (int i = 0; i < Math.min(3, dormant.size()); i++) {
            CategoryScore cs = dormant.get(i);
            int avgInt = (int) Math.round(cs.stat().getAvgLevel());
            int[] range = new int[]{Math.max(avgInt - 3, 1), avgInt};
            RecommendationSlot slot = pickProblem(cs.stat(), range, "REVIEW", usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }

        // Growth 1: INTERMEDIATE + ACTIVE category
        List<CategoryScore> activeIntermediate = scores.stream()
                .filter(s -> "INTERMEDIATE".equals(s.stat().getProficiency()))
                .filter(s -> s.stat().getIsDormant() == null || !s.stat().getIsDormant())
                .filter(s -> !usedTags.contains(s.stat().getTagKey()))
                .toList();
        if (!activeIntermediate.isEmpty()) {
            CategoryScore cs = activeIntermediate.get(0);
            int[] range = computeDifficultyRange(cs.stat(), userTier);
            RecommendationSlot slot = pickProblem(cs.stat(), range, "GROWTH", usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }

        // Explore 1
        RecommendationSlot exploreSlot = pickExploreProblem(tagStats, userTier, usedProblems, usedTags, skippedIds, includeForeign);
        if (exploreSlot != null) {
            slots.add(exploreSlot);
        }

        fillRemainingSlots(slots, scores, userTier, usedProblems, usedTags, skippedIds, 5, includeForeign);

        return slots;
    }

    // ── Problem Picking ──

    private RecommendationSlot pickProblem(UserTagStat stat, int[] range, String slotType,
                                           Set<Integer> usedProblems, Set<Integer> skippedIds, int userTier,
                                           boolean includeForeign) {
        List<Integer> excludeList = new ArrayList<>(usedProblems);
        excludeList.addAll(skippedIds);
        if (excludeList.isEmpty()) excludeList.add(-1);

        List<Problem> candidates = problemRepository.findByTagAndLevelRange(
                stat.getTagKey(), range[0], range[1], excludeList);

        if (candidates.isEmpty()) {
            // Widen range slightly
            int widerLow = Math.max(range[0] - 2, 1);
            int widerHigh = Math.min(range[1] + 2, Math.min(userTier + 5, 30));
            candidates = problemRepository.findByTagAndLevelRange(
                    stat.getTagKey(), widerLow, widerHigh, excludeList);
        }

        if (candidates.isEmpty()) {
            // Fetch from solved.ac API and cache
            fetchAndCacheProblems(stat.getTagKey(), range[0], range[1]);
            candidates = problemRepository.findByTagAndLevelRange(
                    stat.getTagKey(), range[0], range[1], excludeList);
        }

        if (candidates.isEmpty()) return null;

        if (!includeForeign) {
            candidates = candidates.stream()
                    .filter(p -> p.getTitle() != null && KOREAN_PATTERN.matcher(p.getTitle()).find())
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) return null;
        }

        List<Problem> pool = candidates.size() > 20 ? candidates.subList(0, 20) : candidates;
        Problem chosen = pool.get(new Random().nextInt(pool.size()));

        return buildSlot(chosen, slotType, stat);
    }

    /**
     * Fetch problems from solved.ac by tag and level range, then cache in DB.
     */
    private void fetchAndCacheProblems(String tagKey, int minLevel, int maxLevel) {
        try {
            String query = "tag:" + tagKey + " level:" + minLevel + ".." + maxLevel;
            SolvedacSearchResponse response = solvedacClient.searchProblems(query, 1);
            if (response == null || response.getItems() == null) return;

            for (SolvedacProblemItem item : response.getItems()) {
                if (problemRepository.existsById(item.getProblemId())) continue;

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

                        if (!problemTagRepository.existsById(new ProblemTagId(item.getProblemId(), tag.getKey()))) {
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
            log.info("Cached {} problems from solved.ac for tag={} level={}..{}",
                    response.getItems().size(), tagKey, minLevel, maxLevel);
        } catch (Exception e) {
            log.warn("Failed to fetch problems from solved.ac for tag {}: {}", tagKey, e.getMessage());
        }
    }

    private RecommendationSlot pickMultiTagProblem(UserTagStat stat1, UserTagStat stat2,
                                                    int userTier, Set<Integer> usedProblems, Set<Integer> skippedIds,
                                                    boolean includeForeign) {
        int minAvg = (int) Math.min(Math.round(stat1.getAvgLevel()), Math.round(stat2.getAvgLevel()));
        int maxMax = Math.max(stat1.getMaxLevel(), stat2.getMaxLevel());
        int lower = minAvg;
        int upper = Math.min(maxMax + 2, Math.min(userTier + 5, 30));

        List<Integer> excludeList = new ArrayList<>(usedProblems);
        excludeList.addAll(skippedIds);
        if (excludeList.isEmpty()) excludeList.add(-1);

        List<Problem> candidates = problemRepository.findByTwoTagsAndLevelRange(
                stat1.getTagKey(), stat2.getTagKey(), lower, upper, excludeList);

        if (candidates.isEmpty()) return null;

        if (!includeForeign) {
            candidates = candidates.stream()
                    .filter(p -> p.getTitle() != null && KOREAN_PATTERN.matcher(p.getTitle()).find())
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) return null;
        }

        List<Problem> pool = candidates.size() > 10 ? candidates.subList(0, 10) : candidates;
        Problem chosen = pool.get(new Random().nextInt(pool.size()));

        String tagName1 = stat1.getTagName() != null ? stat1.getTagName() : stat1.getTagKey();
        String tagName2 = stat2.getTagName() != null ? stat2.getTagName() : stat2.getTagKey();

        List<String> problemTags = problemTagRepository.findByProblemId(chosen.getId()).stream()
                .map(ProblemTag::getTagKey)
                .collect(Collectors.toList());

        return RecommendationSlot.builder()
                .slotType("CHALLENGE")
                .problem(ProblemInfo.builder()
                        .id(chosen.getId())
                        .title(chosen.getTitle())
                        .level(chosen.getLevel())
                        .levelName(tierName(chosen.getLevel()))
                        .tags(problemTags)
                        .solvedCount(chosen.getSolvedCount())
                        .acceptanceRate(chosen.getAcceptedRate())
                        .build())
                .reason(ReasonInfo.builder()
                        .category(stat1.getTagKey() + "+" + stat2.getTagKey())
                        .categoryName(tagName1 + " + " + tagName2)
                        .proficiency("ADVANCED")
                        .message(tagName1 + "과 " + tagName2 + " 복합 도전 문제")
                        .build())
                .build();
    }

    private RecommendationSlot pickExploreProblem(List<UserTagStat> tagStats, int userTier,
                                                   Set<Integer> usedProblems, Set<String> usedTags,
                                                   Set<Integer> skippedIds, boolean includeForeign) {
        // Find tags from the problem pool that the user hasn't explored
        Set<String> userTags = tagStats.stream().map(UserTagStat::getTagKey).collect(Collectors.toSet());
        userTags.addAll(usedTags);

        // Get some tags from problem_tags that aren't in user's solved tags
        List<String> exploreTags = problemTagRepository.findAll().stream()
                .map(ProblemTag::getTagKey)
                .distinct()
                .filter(t -> !userTags.contains(t))
                .limit(10)
                .collect(Collectors.toList());

        if (exploreTags.isEmpty()) {
            // Fall back to beginner tags with few solves
            exploreTags = tagStats.stream()
                    .filter(s -> "BEGINNER".equals(s.getProficiency()) || "UNEXPLORED".equals(s.getProficiency()))
                    .filter(s -> !usedTags.contains(s.getTagKey()))
                    .map(UserTagStat::getTagKey)
                    .limit(5)
                    .collect(Collectors.toList());
        }

        int upper = Math.min(8, userTier > 5 ? userTier - 5 : userTier);
        upper = Math.max(upper, 1);

        List<Integer> excludeList = new ArrayList<>(usedProblems);
        excludeList.addAll(skippedIds);
        if (excludeList.isEmpty()) excludeList.add(-1);

        for (String tag : exploreTags) {
            List<Problem> candidates = problemRepository.findByTagAndLevelRange(tag, 1, upper, excludeList);
            if (!includeForeign) {
                candidates = candidates.stream()
                        .filter(p -> p.getTitle() != null && KOREAN_PATTERN.matcher(p.getTitle()).find())
                        .collect(Collectors.toList());
            }
            if (!candidates.isEmpty()) {
                List<Problem> pool = candidates.size() > 10 ? candidates.subList(0, 10) : candidates;
                Problem chosen = pool.get(new Random().nextInt(pool.size()));

                String tagName = problemTagRepository.findByProblemId(chosen.getId()).stream()
                        .filter(pt -> pt.getTagKey().equals(tag))
                        .findFirst()
                        .map(ProblemTag::getTagName)
                        .orElse(tag);

                List<String> problemTags = problemTagRepository.findByProblemId(chosen.getId()).stream()
                        .map(ProblemTag::getTagKey)
                        .collect(Collectors.toList());

                return RecommendationSlot.builder()
                        .slotType("EXPLORE")
                        .problem(ProblemInfo.builder()
                                .id(chosen.getId())
                                .title(chosen.getTitle())
                                .level(chosen.getLevel())
                                .levelName(tierName(chosen.getLevel()))
                                .tags(problemTags)
                                .solvedCount(chosen.getSolvedCount())
                                .acceptanceRate(chosen.getAcceptedRate())
                                .build())
                        .reason(ReasonInfo.builder()
                                .category(tag)
                                .categoryName(tagName)
                                .proficiency("UNEXPLORED")
                                .message("새로운 카테고리 도전")
                                .build())
                        .build();
            }
        }
        return null;
    }

    private void fillRemainingSlots(List<RecommendationSlot> slots, List<CategoryScore> scores,
                                     int userTier, Set<Integer> usedProblems, Set<String> usedTags,
                                     Set<Integer> skippedIds, int targetCount, boolean includeForeign) {
        if (slots.size() >= targetCount) return;

        for (CategoryScore cs : scores) {
            if (slots.size() >= targetCount) break;
            if (usedTags.contains(cs.stat().getTagKey())) continue;

            int[] range = computeDifficultyRange(cs.stat(), userTier);
            String slotType = switch (cs.stat().getProficiency()) {
                case "ADVANCED" -> "CHALLENGE";
                case "INTERMEDIATE" -> "GROWTH";
                case "BEGINNER" -> "ENTRY";
                default -> "EXPLORE";
            };

            RecommendationSlot slot = pickProblem(cs.stat(), range, slotType, usedProblems, skippedIds, userTier, includeForeign);
            if (slot != null) {
                slots.add(slot);
                usedTags.add(cs.stat().getTagKey());
                usedProblems.add(slot.getProblem().getId());
            }
        }
    }

    // ── Helpers ──

    private RecommendationSlot buildSlot(Problem problem, String slotType, UserTagStat stat) {
        String tagName = stat.getTagName() != null ? stat.getTagName() : stat.getTagKey();

        List<String> problemTags = problemTagRepository.findByProblemId(problem.getId()).stream()
                .map(ProblemTag::getTagKey)
                .collect(Collectors.toList());

        String message = switch (slotType) {
            case "REVIEW" -> tagName + " 카테고리 복습";
            case "GROWTH" -> tagName + " 카테고리 성장을 위한 문제";
            case "CHALLENGE" -> tagName + " 고난이도 도전";
            case "ENTRY" -> tagName + " 카테고리 기초 다지기";
            case "EXPLORE" -> "새로운 카테고리 도전";
            default -> tagName + " 추천 문제";
        };

        return RecommendationSlot.builder()
                .slotType(slotType)
                .problem(ProblemInfo.builder()
                        .id(problem.getId())
                        .title(problem.getTitle())
                        .level(problem.getLevel())
                        .levelName(tierName(problem.getLevel()))
                        .tags(problemTags)
                        .solvedCount(problem.getSolvedCount())
                        .acceptanceRate(problem.getAcceptedRate())
                        .build())
                .reason(ReasonInfo.builder()
                        .category(stat.getTagKey())
                        .categoryName(tagName)
                        .proficiency(stat.getProficiency())
                        .message(message)
                        .build())
                .build();
    }

    private String tierName(int level) {
        if (level < 0 || level >= TIER_NAMES.length) return "Unknown";
        return TIER_NAMES[level];
    }

    private record CategoryScore(UserTagStat stat, int score) {}
}
