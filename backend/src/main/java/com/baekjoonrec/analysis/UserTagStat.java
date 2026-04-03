package com.baekjoonrec.analysis;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_tag_stats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(UserTagStatId.class)
public class UserTagStat {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "tag_key", length = 100)
    private String tagKey;

    @Column(name = "solved_count")
    private Integer solvedCount;

    @Column(name = "avg_level")
    private Double avgLevel;

    @Column(name = "max_level")
    private Integer maxLevel;

    @Column(name = "last_solved_at")
    private LocalDateTime lastSolvedAt;

    @Column(length = 20)
    private String proficiency;

    @Column(name = "is_dormant")
    private Boolean isDormant;

    @Column(name = "effective_pool_size")
    private Integer effectivePoolSize;

    @Column(name = "near_avg_count")
    private Integer nearAvgCount;

    @Column(name = "tag_name", length = 200)
    private String tagName;
}
