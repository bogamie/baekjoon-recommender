package com.baekjoonrec.user.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSettingsRequest {
    private String theme;
    private Boolean includeForeign;
}
