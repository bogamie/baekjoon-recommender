package com.baekjoonrec.problem;

import lombok.*;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProblemTagId implements Serializable {
    private Integer problemId;
    private String tagKey;
}
