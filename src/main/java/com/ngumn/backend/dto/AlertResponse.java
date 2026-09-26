package com.ngumn.backend.dto;

import com.ngumn.backend.entity.Alert;
import com.ngumn.backend.entity.AlertType;
import com.ngumn.backend.entity.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class AlertResponse {
    private Long id;
    private Long vehicleId;
    private AlertType type;
    private RiskLevel riskLevel;
    private String message;
    private Double latitude;
    private Double longitude;
    private Boolean acknowledged;
    private LocalDateTime createdAt;

    public static AlertResponse from(Alert a) {
        return new AlertResponse(
                a.getId(),
                a.getVehicle() != null ? a.getVehicle().getId() : null,
                a.getType(), a.getRiskLevel(), a.getMessage(),
                a.getLatitude(), a.getLongitude(), a.getAcknowledged(), a.getCreatedAt()
        );
    }
}
