package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The ambulance's destination hospital (picked from nearby hospitals in the app). */
@Data
public class HospitalRequest {
    @NotBlank
    @Size(max = 120)
    private String name;

    private Double latitude;
    private Double longitude;
}
