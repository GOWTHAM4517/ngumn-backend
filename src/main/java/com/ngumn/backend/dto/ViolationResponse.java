package com.ngumn.backend.dto;

import com.ngumn.backend.entity.TrafficViolation;
import com.ngumn.backend.entity.ViolationSource;
import com.ngumn.backend.entity.ViolationType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ViolationResponse {
    private Long id;
    private Long vehicleId;
    private String vehicleCode;
    private String driverName;
    private ViolationType type;
    private ViolationSource source;
    private Double latitude;
    private Double longitude;
    private Double speedKmh;
    private Integer limitKmh;
    private Double headingDegrees;
    private Integer occupants;
    private Integer seatCapacity;
    private String zoneName;
    private Long complaintId;
    private String message;
    private Boolean simulated;
    private LocalDateTime createdAt;

    public static ViolationResponse from(TrafficViolation v) {
        return new ViolationResponse(
                v.getId(),
                v.getVehicle() != null ? v.getVehicle().getId() : null,
                v.getVehicle() != null ? v.getVehicle().getVehicleCode() : null,
                v.getDriver() != null ? v.getDriver().getName() : null,
                v.getType(),
                v.getSource(),
                v.getLatitude(), v.getLongitude(),
                v.getSpeedKmh(), v.getLimitKmh(), v.getHeadingDegrees(),
                v.getOccupants(), v.getSeatCapacity(),
                v.getZoneName(), v.getComplaintId(), v.getMessage(), v.getSimulated(), v.getCreatedAt()
        );
    }
}
