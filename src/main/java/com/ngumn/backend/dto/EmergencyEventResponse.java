package com.ngumn.backend.dto;

import com.ngumn.backend.entity.EmergencyEvent;
import com.ngumn.backend.entity.EmergencyStatus;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class EmergencyEventResponse {
    private Long id;
    private Long vehicleId;
    private String vehicleCode;
    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;
    private EmergencyStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public static EmergencyEventResponse from(EmergencyEvent e) {
        return new EmergencyEventResponse(
                e.getId(),
                e.getVehicle() != null ? e.getVehicle().getId() : null,
                e.getVehicle() != null ? e.getVehicle().getVehicleCode() : null,
                e.getOriginLatitude(), e.getOriginLongitude(),
                e.getDestinationLatitude(), e.getDestinationLongitude(),
                e.getStatus(), e.getStartedAt(), e.getEndedAt()
        );
    }
}
