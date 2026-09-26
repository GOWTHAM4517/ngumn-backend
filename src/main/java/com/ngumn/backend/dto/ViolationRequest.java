package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ViolationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Sent by Drive Guard on the driver's phone when it detects a violation.
 * Which optional fields matter depends on the type:
 * OVERSPEED needs speedKmh + limitKmh, OVERLOAD needs occupants +
 * seatCapacity, WRONG_WAY / NO_ENTRY usually carry zoneName and heading.
 */
@Data
public class ViolationRequest {

    @NotNull
    private Long vehicleId;

    @NotNull
    private ViolationType type;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Double speedKmh;

    private Integer limitKmh;

    private Double headingDegrees;

    private Integer occupants;

    private Integer seatCapacity;

    @Size(max = 150)
    private String zoneName;

    /** True for the app's Test Drive - stored, but kept apart from real violations. */
    private Boolean simulated;
}
