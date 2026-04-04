package com.baekjoonrec.recommend;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "problem_id", nullable = false)
    private Integer problemId;

    @Column(name = "slot_type", length = 20, nullable = false)
    private String slotType;

    @Column(name = "target_tag", length = 100)
    private String targetTag;

    @Column(name = "recommended_at")
    private LocalDateTime recommendedAt;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "skipped_at")
    private LocalDateTime skippedAt;

    @PrePersist
    protected void onCreate() {
        if (recommendedAt == null) recommendedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }
}
