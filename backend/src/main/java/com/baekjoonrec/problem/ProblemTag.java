package com.baekjoonrec.problem;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "problem_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ProblemTagId.class)
public class ProblemTag {

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    @Id
    @Column(name = "tag_key", length = 100)
    private String tagKey;

    @Column(name = "tag_name", length = 200)
    private String tagName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", insertable = false, updatable = false)
    private Problem problem;
}
