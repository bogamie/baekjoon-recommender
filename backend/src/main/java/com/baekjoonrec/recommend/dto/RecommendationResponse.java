package com.baekjoonrec.recommend.dto;

import lombok.*;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationResponse {
    private List<RecommendationSlot> recommendations;
    private UserProfileSummary userProfile;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RecommendationSlot {
        private String slotType;
        private ProblemInfo problem;
        private ReasonInfo reason;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ProblemInfo {
        private Integer id;
        private String title;
        private Integer level;
        private String levelName;
        private List<String> tags;
        private Integer solvedCount;
        private Double acceptanceRate;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReasonInfo {
        private String category;
        private String categoryName;
        private String proficiency;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UserProfileSummary {
        private String tier;
        private Integer totalSolved;
        private Integer advancedCount;
        private Integer intermediateCount;
        private Integer beginnerCount;
    }
}
