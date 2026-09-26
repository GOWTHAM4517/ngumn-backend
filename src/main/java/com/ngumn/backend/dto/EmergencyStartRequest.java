package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EmergencyStartRequest {

    @NotNull
    private Long vehicleId;

    private Double originLatitude;
    private Double originLongitude;

    @NotNull
    private Double destinationLatitude;

    @NotNull
    private Double destinationLongitude;
}
