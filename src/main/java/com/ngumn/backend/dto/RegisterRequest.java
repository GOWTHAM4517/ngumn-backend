package com.ngumn.backend.dto;

import com.ngumn.backend.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    /**
     * DRIVER (the default), PEDESTRIAN or EMERGENCY. How someone travels day
     * to day is switched in the app (walking, bike, car...), so this mostly
     * matters for emergency-service accounts. Admin accounts can't be
     * created here.
     */
    private Role role;
}
