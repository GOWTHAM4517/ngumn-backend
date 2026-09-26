package com.ngumn.backend.service;

import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.dto.NearbyVehicleResponse;
import com.ngumn.backend.dto.VehicleCreateRequest;
import com.ngumn.backend.dto.VehicleResponse;
import com.ngumn.backend.entity.LocationLog;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.LocationLogRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final LocationLogRepository locationLogRepository;
    private final NgumnWebSocketHandler webSocketHandler;
    private final RiskEngineService riskEngineService;
    private final RuleMonitorService ruleMonitorService;

    public VehicleService(VehicleRepository vehicleRepository,
                           LocationLogRepository locationLogRepository,
                           NgumnWebSocketHandler webSocketHandler,
                           RiskEngineService riskEngineService,
                           RuleMonitorService ruleMonitorService) {
        this.vehicleRepository = vehicleRepository;
        this.locationLogRepository = locationLogRepository;
        this.webSocketHandler = webSocketHandler;
        this.riskEngineService = riskEngineService;
        this.ruleMonitorService = ruleMonitorService;
    }

    public VehicleResponse register(User owner, VehicleCreateRequest request) {
        if (vehicleRepository.findByVehicleCode(request.getVehicleCode()).isPresent()) {
            throw ApiException.badRequest("A vehicle with this code is already registered");
        }
        Vehicle vehicle = Vehicle.builder()
                .vehicleCode(request.getVehicleCode())
                .owner(owner)
                .vehicleType(request.getVehicleType())
                .status(VehicleStatus.ACTIVE)
                .emergencyStatus(false)
                .isSimulated(false)
                .speedKmh(0.0)
                .directionDegrees(0.0)
                .build();
        return VehicleResponse.from(vehicleRepository.save(vehicle));
    }

    public List<VehicleResponse> myVehicles(Long ownerId) {
        return vehicleRepository.findByOwnerId(ownerId).stream()
                .map(VehicleResponse::from).collect(Collectors.toList());
    }

    public List<VehicleResponse> allActive() {
        return vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .map(VehicleResponse::from).collect(Collectors.toList());
    }

    public Vehicle getOwnedVehicleOrThrow(Long vehicleId, Long ownerId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> ApiException.notFound("Vehicle not found"));
        if (!vehicle.getOwner().getId().equals(ownerId)) {
            throw ApiException.forbidden("You do not own this vehicle");
        }
        return vehicle;
    }

    public VehicleResponse updateLocation(Vehicle vehicle, LocationUpdateRequest request) {
        // Where the vehicle was before this update - the rule monitor uses
        // it to work out the real direction of travel.
        Double prevLat = vehicle.getCurrentLatitude();
        Double prevLng = vehicle.getCurrentLongitude();
        LocalDateTime prevTime = vehicle.getLastLocationUpdate();

        vehicle.setCurrentLatitude(request.getLatitude());
        vehicle.setCurrentLongitude(request.getLongitude());
        vehicle.setSpeedKmh(request.getSpeedKmh() != null ? request.getSpeedKmh() : 0.0);
        vehicle.setDirectionDegrees(request.getDirectionDegrees() != null ? request.getDirectionDegrees() : 0.0);
        vehicle.setLastLocationUpdate(LocalDateTime.now());
        vehicle = vehicleRepository.save(vehicle);

        locationLogRepository.save(LocationLog.builder()
                .vehicle(vehicle)
                .latitude(vehicle.getCurrentLatitude())
                .longitude(vehicle.getCurrentLongitude())
                .speedKmh(vehicle.getSpeedKmh())
                .directionDegrees(vehicle.getDirectionDegrees())
                .timestamp(LocalDateTime.now())
                .build());

        VehicleResponse response = VehicleResponse.from(vehicle);
        webSocketHandler.broadcast("VEHICLE_UPDATE", response);

        // Run risk analysis synchronously for the prototype - simple and
        // easy to demo (every location update produces a fresh risk score).
        riskEngineService.evaluate(vehicle);

        // Automatic traffic-rule checks (speed zones, one-way roads,
        // no-entry zones) - see RuleMonitorService.
        ruleMonitorService.check(vehicle, request, prevLat, prevLng, prevTime);

        return response;
    }

    public List<NearbyVehicleResponse> nearby(Vehicle vehicle, double radiusMeters) {
        if (vehicle.getCurrentLatitude() == null || vehicle.getCurrentLongitude() == null) {
            throw ApiException.badRequest("Vehicle has no known location yet");
        }
        return vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(v -> !v.getId().equals(vehicle.getId()))
                .filter(v -> v.getCurrentLatitude() != null && v.getCurrentLongitude() != null)
                .map(v -> {
                    double distance = GeoUtil.distanceMeters(
                            vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude(),
                            v.getCurrentLatitude(), v.getCurrentLongitude());
                    return new NearbyVehicleResponse(v.getId(), v.getVehicleCode(), v.getVehicleType(),
                            distance, v.getCurrentLatitude(), v.getCurrentLongitude(),
                            v.getSpeedKmh(), v.getDirectionDegrees(), v.getEmergencyStatus(), v.getIsSimulated());
                })
                .filter(r -> r.getDistanceMeters() <= radiusMeters)
                .sorted((a, b) -> Double.compare(a.getDistanceMeters(), b.getDistanceMeters()))
                .collect(Collectors.toList());
    }

    public Vehicle findByIdOrThrow(Long id) {
        return vehicleRepository.findById(id).orElseThrow(() -> ApiException.notFound("Vehicle not found"));
    }
}
