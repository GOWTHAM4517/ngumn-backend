package com.ngumn.backend.dto;

import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import com.ngumn.backend.entity.VehicleType;
import com.ngumn.backend.util.VehicleLabels;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A vehicle as other people see it. The owner is given by first name only
 * (the app shows "Ravi's car"), plus the id so the app can recognise every
 * vehicle that belongs to the person looking - never shown as "someone
 * else". vehicleCode is an internal id and is not meant to be displayed.
 */
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
    private Long ownerId;
    /** How the owner is travelling now: WALK, CYCLE, BIKE, AUTO, CAR, BUS, TRUCK or EMERGENCY. */
    private TravelMode travelMode;
    private String plateNumber;

    public static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(
                v.getId(),
                v.getVehicleCode(),
                v.getVehicleType(),
                v.getOwner() != null ? VehicleLabels.firstName(v.getOwner().getName()) : null,
                v.getCurrentLatitude(),
                v.getCurrentLongitude(),
                v.getSpeedKmh(),
                v.getDirectionDegrees(),
                v.getStatus(),
                v.getEmergencyStatus(),
                v.getIsSimulated(),
                v.getLastLocationUpdate(),
                v.getOwner() != null ? v.getOwner().getId() : null,
                v.effectiveTravelMode(),
                v.getPlateNumber()
        );
    }
}
