package com.baekjoonrec.analysis;

import com.baekjoonrec.analysis.dto.DashboardSummaryResponse;
import com.baekjoonrec.analysis.dto.TagStatResponse;
import com.baekjoonrec.auth.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final AnalysisService analysisService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> summary(@AuthenticationPrincipal User user) {
        // Read cached analysis — no external API calls, no re-computation
        UserAnalysis analysis = analysisService.getUserAnalysis(user.getId());

        if (analysis == null) {
            // No analysis yet — return minimal response so frontend knows to sync
            DashboardSummaryResponse response = DashboardSummaryResponse.builder()
                    .totalSolved(0)
                    .solvedacHandle(user.getSolvedacHandle())
                    .build();
            return ResponseEntity.ok(response);
        }

        DashboardSummaryResponse response = DashboardSummaryResponse.builder()
                .solvedacTier(analysis.getTier())
                .rating(analysis.getRating())
                .totalSolved(analysis.getTotalSolved())
                .globalStatus(analysis.getGlobalStatus())
                .solvedacHandle(user.getSolvedacHandle())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TagStatResponse>> tags(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "solvedCount") String sort,
            @RequestParam(defaultValue = "desc") String order) {

        List<UserTagStat> stats = analysisService.getUserTagStats(user.getId());

        Comparator<TagStatResponse> comparator = switch (sort) {
            case "avgLevel" -> Comparator.comparing(TagStatResponse::getAvgLevel, Comparator.nullsLast(Comparator.naturalOrder()));
            case "maxLevel" -> Comparator.comparing(TagStatResponse::getMaxLevel, Comparator.nullsLast(Comparator.naturalOrder()));
            case "tagKey" -> Comparator.comparing(TagStatResponse::getTagKey, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(TagStatResponse::getSolvedCount, Comparator.nullsLast(Comparator.naturalOrder()));
        };

        if ("desc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }

        List<TagStatResponse> result = stats.stream()
                .map(s -> TagStatResponse.builder()
                        .tagKey(s.getTagKey())
                        .solvedCount(s.getSolvedCount())
                        .avgLevel(s.getAvgLevel())
                        .maxLevel(s.getMaxLevel())
                        .lastSolvedAt(s.getLastSolvedAt())
                        .proficiency(s.getProficiency())
                        .isDormant(s.getIsDormant())
                        .build())
                .sorted(comparator)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
