package com.ngumn.backend.service;

import com.ngumn.backend.dto.AlertResponse;
import com.ngumn.backend.entity.Alert;
import com.ngumn.backend.entity.AlertType;
import com.ngumn.backend.entity.RiskLevel;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.event.AlertRaisedEvent;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.AlertRepository;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final NgumnWebSocketHandler webSocketHandler;
    private final ApplicationEventPublisher eventPublisher;

    public AlertService(AlertRepository alertRepository, NgumnWebSocketHandler webSocketHandler,
                         ApplicationEventPublisher eventPublisher) {
        this.alertRepository = alertRepository;
        this.webSocketHandler = webSocketHandler;
        this.eventPublisher = eventPublisher;
    }

    public Alert raise(Vehicle vehicle, User user, AlertType type, RiskLevel level, String message,
                        Double lat, Double lon) {
        Alert alert = Alert.builder()
                .vehicle(vehicle)
                .user(user)
                .type(type)
                .riskLevel(level)
                .message(message)
                .latitude(lat)
                .longitude(lon)
                .acknowledged(false)
                .build();
        alert = alertRepository.save(alert);
        AlertResponse response = AlertResponse.from(alert);
        webSocketHandler.broadcast("ALERT", response);

        // Published as an event (not a direct call) so this service does not
        // need a compile-time dependency on MqttService - that would create
        // a circular bean dependency (AlertService -> MqttService ->
        // VehicleService -> RiskEngineService -> AlertService).
        if (vehicle != null) {
            eventPublisher.publishEvent(new AlertRaisedEvent(vehicle.getVehicleCode(), response));
        }
        return alert;
    }

    public List<AlertResponse> recent() {
        return alertRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(AlertResponse::from).collect(Collectors.toList());
    }

    public List<AlertResponse> forVehicle(Long vehicleId) {
        return alertRepository.findByVehicleIdOrderByCreatedAtDesc(vehicleId).stream()
                .map(AlertResponse::from).collect(Collectors.toList());
    }

    public AlertResponse acknowledge(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> ApiException.notFound("Alert not found"));
        alert.setAcknowledged(true);
        return AlertResponse.from(alertRepository.save(alert));
    }
}
