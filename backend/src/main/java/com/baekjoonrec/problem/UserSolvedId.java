package com.baekjoonrec.problem;

import lombok.*;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserSolvedId implements Serializable {
    private Long userId;
    private Integer problemId;
}
