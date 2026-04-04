package com.baekjoonrec.problem;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_solved")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(UserSolvedId.class)
public class UserSolved {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "problem_id")
    private Integer problemId;

    @Column(name = "solved_at")
    private LocalDateTime solvedAt;
}
