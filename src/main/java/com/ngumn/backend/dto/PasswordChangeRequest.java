package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PasswordChangeRequest {
    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, message = "Use at least 8 characters")
    private String newPassword;
}
