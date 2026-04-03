package com.baekjoonrec.analysis.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryResponse {
    private Integer solvedacTier;
    private Integer rating;
    private Integer totalSolved;
    private String globalStatus;
    private String solvedacHandle;
}
