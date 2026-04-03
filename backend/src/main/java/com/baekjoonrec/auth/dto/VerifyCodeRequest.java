package com.baekjoonrec.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyCodeRequest {
    @NotBlank @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Code is required")
    private String code;
}
