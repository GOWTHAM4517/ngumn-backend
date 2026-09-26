package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ViolationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * "Report a rule-breaker" from the app. The vehicle is optional: pick an
 * NGUMN vehicle nearby (accusedVehicleId), type its number plate
 * (plateNumber), or leave both empty if you didn't catch it.
 */
@Data
public class ComplaintRequest {

    @NotNull
    private ViolationType type;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    @Size(max = 500)
    private String description;

    private Long accusedVehicleId;

    @Size(max = 20)
    private String plateNumber;
}
