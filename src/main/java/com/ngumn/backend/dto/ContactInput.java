package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One emergency contact as the app sends it. */
@Data
public class ContactInput {
    @NotBlank
    @Size(max = 80)
    private String name;

    @NotBlank
    @Size(max = 20)
    private String phone;

    @Size(max = 40)
    private String relation;
}
