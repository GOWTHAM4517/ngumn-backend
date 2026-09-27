package com.ngumn.backend.service;

import com.ngumn.backend.dto.EmergencyStartRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.EmergencyEventRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.VehicleLabels;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * "Green Corridor" priority-route module.
 *
 * IMPORTANT (prototype boundary): this is a SOFTWARE SIMULATION only.
 * It does not control any real traffic signal, and it does not connect
 * to any real emergency-service, hospital, or government system.
 */
@Service
public class EmergencyService {

    private final EmergencyEventRepository emergencyEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final RiskEngineService riskEngineService;
    private final NgumnWebSocketHandler webSocketHandler;

    public EmergencyService(EmergencyEventRepository emergencyEventRepository, VehicleRepository vehicleRepository,
                             AlertService alertService, RiskEngineService riskEngineService,
                             NgumnWebSocketHandler webSocketHandler) {
        this.emergencyEventRepository = emergencyEventRepository;
        this.vehicleRepository = vehicleRepository;
        this.alertService = alertService;
        this.riskEngineService = riskEngineService;
        this.webSocketHandler = webSocketHandler;
    }

    public EmergencyEvent start(EmergencyStartRequest request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> ApiException.notFound("Vehicle not found"));

        vehicle.setEmergencyStatus(true);
        vehicle = vehicleRepository.save(vehicle);

        EmergencyEvent event = EmergencyEvent.builder()
                .vehicle(vehicle)
                .originLatitude(request.getOriginLatitude() != null ? request.getOriginLatitude() : vehicle.getCurrentLatitude())
                .originLongitude(request.getOriginLongitude() != null ? request.getOriginLongitude() : vehicle.getCurrentLongitude())
                .destinationLatitude(request.getDestinationLatitude())
                .destinationLongitude(request.getDestinationLongitude())
                .status(EmergencyStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .build();
        event = emergencyEventRepository.save(event);

        webSocketHandler.broadcast("EMERGENCY_STARTED", Map.of("id", event.getId()));

        // Simulated Green Corridor: tell everyone near it (with a live
        // position) where it is, in words - "An emergency vehicle is 400 m
        // behind you - please give way."
        if (vehicle.getCurrentLatitude() != null && vehicle.getCurrentLongitude() != null) {
            double lat = vehicle.getCurrentLatitude(), lng = vehicle.getCurrentLongitude();
            Double heading = vehicle.getSpeedKmh() != null && vehicle.getSpeedKmh() >= 8 ? vehicle.getDirectionDegrees() : null;
            String who = VehicleLabels.capitalise(emergencyWho(vehicle));
            List<Vehicle> nearby = riskEngineService.findNearbyVehicles(vehicle, 1000);
            for (Vehicle v : nearby) {
                double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lng);
                String where = NearbyDangerService.describe(v, lat, lng, d, heading);
                String advice = v.effectiveTravelMode().onFoot() ? "keep clear of the road" : "please give way";
                alertService.raise(v, v.getOwner(), AlertType.EMERGENCY_VEHICLE, RiskLevel.HIGH,
                        who + " is " + where + " - " + advice + ".",
                        v.getCurrentLatitude(), v.getCurrentLongitude());
            }
        }

        return event;
    }

    public EmergencyEvent end(Long eventId) {
        EmergencyEvent event = emergencyEventRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("Emergency event not found"));
        event.setStatus(EmergencyStatus.COMPLETED);
        event.setEndedAt(LocalDateTime.now());
        event = emergencyEventRepository.save(event);

        Vehicle vehicle = event.getVehicle();
        vehicle.setEmergencyStatus(false);
        vehicleRepository.save(vehicle);

        webSocketHandler.broadcast("EMERGENCY_ENDED", Map.of("id", event.getId()));
        return event;
    }

    /** "an emergency vehicle", or "a car on an emergency" for someone rushing in their own vehicle. */
    private static String emergencyWho(Vehicle vehicle) {
        String plate = VehicleLabels.cleanPlate(vehicle.getPlateNumber());
        TravelMode mode = vehicle.effectiveTravelMode();
        String base = mode == TravelMode.EMERGENCY ? "an emergency vehicle"
                : mode.onFoot() ? "someone on an emergency" : mode.withArticle() + " on an emergency";
        return plate != null && !mode.onFoot() ? base + " (" + plate + ")" : base;
    }

    public List<EmergencyEvent> active() {
        return emergencyEventRepository.findByStatus(EmergencyStatus.ACTIVE);
    }
}
