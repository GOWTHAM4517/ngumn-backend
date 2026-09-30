package com.ngumn.backend.service;

import com.ngumn.backend.dto.AlertResponse;
import com.ngumn.backend.entity.Alert;
import com.ngumn.backend.entity.AlertType;
import com.ngumn.backend.entity.RiskLevel;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.event.AlertRaisedEvent;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.AlertRepository;
import com.ngumn.backend.util.GeoUtil;
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
        return raise(vehicle, user, type, level, message, lat, lon, null, null);
    }

    /** An alert about a particular hazard report or rule-breaker report (either id may be null). */
    public Alert raise(Vehicle vehicle, User user, AlertType type, RiskLevel level, String message,
                        Double lat, Double lon, Long reportId, Long complaintId) {
        Alert alert = Alert.builder()
                .vehicle(vehicle)
                .user(user)
                .type(type)
                .riskLevel(level)
                .message(message)
                .latitude(lat)
                .longitude(lon)
                .reportId(reportId)
                .complaintId(complaintId)
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

    /**
     * A note to one person about a help request (see IncidentService): only
     * they get it. The live channel just says "something new for someone"
     * - the text isn't sent to everyone connected.
     */
    public Alert raiseAboutIncident(User user, AlertType type, RiskLevel level, String message,
                                    Double lat, Double lon, Long incidentId) {
        Alert alert = Alert.builder()
                .user(user)
                .type(type)
                .riskLevel(level)
                .message(message)
                .latitude(lat)
                .longitude(lon)
                .incidentId(incidentId)
                .acknowledged(false)
                .build();
        alert = alertRepository.save(alert);
        webSocketHandler.broadcast("ALERT", java.util.Map.of("id", alert.getId(), "userId", user.getId()));
        return alert;
    }

    public List<AlertResponse> recent() {
        return recent(null, null, null);
    }

    /** The latest alerts - with lat / lng, only ones raised within radiusMeters of that point. */
    public List<AlertResponse> recent(Double lat, Double lng, Double radiusMeters) {
        var latest = alertRepository.findTop100ByOrderByCreatedAtDesc().stream();
        if (lat != null && lng != null) {
            double radius = VehicleService.clampRadius(radiusMeters, 10_000);
            latest = latest.filter(a -> a.getLatitude() != null && a.getLongitude() != null
                    && GeoUtil.distanceMeters(lat, lng, a.getLatitude(), a.getLongitude()) <= radius);
        }
        return latest.map(AlertResponse::from).collect(Collectors.toList());
    }

    public List<AlertResponse> forVehicle(Long vehicleId) {
        return alertRepository.findByVehicleIdOrderByCreatedAtDesc(vehicleId).stream()
                .map(AlertResponse::from).collect(Collectors.toList());
    }

    /** A vehicle's alerts - only the ones meant for `viewer` (admins see all). */
    public List<AlertResponse> forVehicle(User viewer, Long vehicleId) {
        return alertRepository.findByVehicleIdOrderByCreatedAtDesc(vehicleId).stream()
                .filter(a -> isFor(a, viewer))
                .map(AlertResponse::from).collect(Collectors.toList());
    }

    /**
     * Everything raised for this person, newest first - whichever of their
     * vehicles it came through (older accounts may have more than one).
     */
    public List<AlertResponse> forUser(User user) {
        return alertRepository.findTop100ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(AlertResponse::from).collect(Collectors.toList());
    }

    /**
     * This person's alerts newer than `afterId` (at most 20), newest first -
     * a light check the app makes every few seconds while it's in the
     * background, so it can show a notification as soon as something new
     * arrives.
     */
    public List<AlertResponse> forUserAfter(User user, long afterId) {
        List<AlertResponse> newer = alertRepository.findTop20ByUserIdAndIdGreaterThanOrderByIdAsc(user.getId(), afterId).stream()
                .map(AlertResponse::from).collect(Collectors.toList());
        java.util.Collections.reverse(newer);
        return newer;
    }

    public AlertResponse acknowledge(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> ApiException.notFound("Alert not found"));
        alert.setAcknowledged(true);
        return AlertResponse.from(alertRepository.save(alert));
    }

    /** Marks one of `viewer`'s alerts as read - nobody can mark someone else's. */
    public AlertResponse acknowledge(User viewer, Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> ApiException.notFound("Alert not found"));
        if (!isFor(alert, viewer)) {
            throw ApiException.forbidden("That alert belongs to someone else");
        }
        alert.setAcknowledged(true);
        return AlertResponse.from(alertRepository.save(alert));
    }

    private static boolean isFor(Alert alert, User viewer) {
        return viewer.getRole() == Role.ADMIN || alert.getUser() == null
                || (alert.getUser().getId() != null && alert.getUser().getId().equals(viewer.getId()));
    }
}
