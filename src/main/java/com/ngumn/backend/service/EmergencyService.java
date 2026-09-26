package com.ngumn.backend.service;

import com.ngumn.backend.dto.EmergencyStartRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.EmergencyEventRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

        webSocketHandler.broadcast("EMERGENCY_STARTED", event);

        // Simulated Green Corridor: notify every currently-nearby vehicle.
        if (vehicle.getCurrentLatitude() != null) {
            List<Vehicle> nearby = riskEngineService.findNearbyVehicles(vehicle, 1000);
            for (Vehicle v : nearby) {
                alertService.raise(v, v.getOwner(), AlertType.EMERGENCY_VEHICLE, RiskLevel.HIGH,
                        "Emergency vehicle " + vehicle.getVehicleCode() + " approaching - please give way.",
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

        webSocketHandler.broadcast("EMERGENCY_ENDED", event);
        return event;
    }

    public List<EmergencyEvent> active() {
        return emergencyEventRepository.findByStatus(EmergencyStatus.ACTIVE);
    }
}
