package com.baekjoonrec.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateHandleRequest {
    @NotBlank(message = "Handle is required")
    private String handle;
}
