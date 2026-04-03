package com.baekjoonrec.analysis.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TagStatResponse {
    private String tagKey;
    private Integer solvedCount;
    private Double avgLevel;
    private Integer maxLevel;
    private LocalDateTime lastSolvedAt;
    private String proficiency;
    private Boolean isDormant;
}
