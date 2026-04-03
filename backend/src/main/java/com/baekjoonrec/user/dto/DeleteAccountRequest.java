package com.baekjoonrec.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeleteAccountRequest {
    @NotBlank(message = "Password is required")
    private String password;
}
