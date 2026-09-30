package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ResponderTeam;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin dashboard > Responders: make a police / ambulance / department
 * account (or turn an existing account into one - then the password is left
 * as it is if blank).
 */
@Data
public class ResponderCreateRequest {

    /** The person's name. */
    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    /** Needed for a new account; optional when the email already has one. */
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @NotNull
    private ResponderTeam team;

    /** "Traffic Police - Benz Circle", "108 Ambulance - Unit 4". */
    @NotBlank
    @Size(max = 120)
    private String unitName;
}
