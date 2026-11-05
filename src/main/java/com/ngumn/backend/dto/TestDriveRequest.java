package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A showroom starts a customer's test drive. */
@Data
public class TestDriveRequest {
    @NotBlank
    @Size(max = 80)
    private String customerName;

    @Size(max = 20)
    private String customerPhone;

    /** How long they have (default 30). */
    @Min(10)
    @Max(240)
    private Integer minutes;

    /** How far from the showroom they may go (default 5 km). */
    @Min(1000)
    @Max(50000)
    private Integer radiusM;
}
