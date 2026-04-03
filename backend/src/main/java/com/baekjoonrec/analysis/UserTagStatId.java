package com.baekjoonrec.analysis;

import lombok.*;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserTagStatId implements Serializable {
    private Long userId;
    private String tagKey;
}
