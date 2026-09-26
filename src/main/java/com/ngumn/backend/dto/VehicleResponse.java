package com.ngumn.backend.dto;

import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import com.ngumn.backend.entity.VehicleType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class VehicleResponse {
    private Long id;
    private String vehicleCode;
    private VehicleType vehicleType;
    private String ownerName;
    private Double currentLatitude;
    private Double currentLongitude;
    private Double speedKmh;
    private Double directionDegrees;
    private VehicleStatus status;
    private Boolean emergencyStatus;
    private Boolean isSimulated;
    private LocalDateTime lastLocationUpdate;

    public static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(
                v.getId(),
                v.getVehicleCode(),
                v.getVehicleType(),
                v.getOwner() != null ? v.getOwner().getName() : null,
                v.getCurrentLatitude(),
                v.getCurrentLongitude(),
                v.getSpeedKmh(),
                v.getDirectionDegrees(),
                v.getStatus(),
                v.getEmergencyStatus(),
                v.getIsSimulated(),
                v.getLastLocationUpdate()
        );
    }
}
