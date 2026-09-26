package com.ngumn.backend.dto;

import com.ngumn.backend.entity.VehicleType;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NearbyVehicleResponse {
    private Long vehicleId;
    private String vehicleCode;
    private VehicleType vehicleType;
    private double distanceMeters;
    private Double latitude;
    private Double longitude;
    private Double speedKmh;
    private Double directionDegrees;
    private Boolean emergencyStatus;
    private Boolean isSimulated;
}
