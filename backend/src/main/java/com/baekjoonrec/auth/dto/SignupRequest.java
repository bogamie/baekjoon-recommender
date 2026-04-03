package com.baekjoonrec.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class SignupRequest {
    @NotBlank @Email(message = "Invalid email format")
    private String email;

    @NotBlank
    @Size(min = 4, max = 20, message = "Username must be 4-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "Username must be alphanumeric")
    private String username;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).+$",
            message = "Password must contain letter, number, and special character")
    private String password;

    private String solvedacHandle;

    private List<String> interests;
}
