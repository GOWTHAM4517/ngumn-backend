package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LocationUpdateRequest {

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Double speedKmh;

    private Double directionDegrees;

    /** GPS accuracy of the fix in metres, when the phone gives it (helps match the right road). */
    private Double accuracyMeters;
}
