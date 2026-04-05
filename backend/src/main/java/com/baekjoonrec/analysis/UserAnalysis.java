package com.baekjoonrec.analysis;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_analysis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAnalysis {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "global_status", length = 30)
    private String globalStatus;

    @Column(name = "gap_days")
    private Integer gapDays;

    @Column(name = "active_days_90")
    private Integer activeDays90;

    @Column(name = "previous_gap_days")
    private Integer previousGapDays;

    @Column(name = "active_days_in_block")
    private Integer activeDaysInBlock;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "total_solved")
    private Integer totalSolved;

    @Column(name = "tier")
    private Integer tier;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        analyzedAt = LocalDateTime.now();
    }
}
