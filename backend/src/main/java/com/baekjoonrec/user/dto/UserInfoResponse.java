package com.baekjoonrec.user.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInfoResponse {
    private Long id;
    private String email;
    private String username;
    private String solvedacHandle;
    private Boolean emailVerified;
    private String theme;
    private Boolean includeForeign;
}
