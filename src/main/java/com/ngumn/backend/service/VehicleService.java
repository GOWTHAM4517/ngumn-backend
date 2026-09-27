package com.ngumn.backend.service;

import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.dto.NearbyVehicleResponse;
import com.ngumn.backend.dto.VehicleCreateRequest;
import com.ngumn.backend.dto.VehicleResponse;
import com.ngumn.backend.dto.VehicleUpdateRequest;
import com.ngumn.backend.entity.LocationLog;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.LocationLogRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.VehicleLabels;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Vehicles - or rather, people on the move: each account has one, and its
 * travel mode says whether they're walking, riding or driving right now
 * (switchable any time from the app).
 *
 * Only vehicles whose position is live (updated in the last 10 minutes)
 * are listed for other people, so nobody sees "ghost" vehicles left where
 * someone stopped sharing their location hours ago. The list can be
 * limited to a radius around a point - the app asks only for what's
 * around the person looking.
 */
@Service
public class VehicleService {

    /** A position older than this isn't shown to anyone else any more. */
    public static final long LIVE_SECONDS = 10 * 60;
    /** Largest area the app can ask about at once. */
    public static final double MAX_RADIUS_M = 20_000;

    private final VehicleRepository vehicleRepository;
    private final LocationLogRepository locationLogRepository;
    private final UserRepository userRepository;
    private final NgumnWebSocketHandler webSocketHandler;
    private final RiskEngineService riskEngineService;
    private final RuleMonitorService ruleMonitorService;
    private final HazardAheadService hazardAheadService;

    public VehicleService(VehicleRepository vehicleRepository,
                           LocationLogRepository locationLogRepository,
                           UserRepository userRepository,
                           NgumnWebSocketHandler webSocketHandler,
                           RiskEngineService riskEngineService,
                           RuleMonitorService ruleMonitorService,
                           HazardAheadService hazardAheadService) {
        this.vehicleRepository = vehicleRepository;
        this.locationLogRepository = locationLogRepository;
        this.userRepository = userRepository;
        this.webSocketHandler = webSocketHandler;
        this.riskEngineService = riskEngineService;
        this.ruleMonitorService = ruleMonitorService;
        this.hazardAheadService = hazardAheadService;
    }

    public VehicleResponse register(User owner, VehicleCreateRequest request) {
        if (vehicleRepository.findByVehicleCode(request.getVehicleCode()).isPresent()) {
            throw ApiException.badRequest("A vehicle with this code is already registered");
        }
        TravelMode mode = request.getTravelMode() != null ? request.getTravelMode()
                : owner.getRole() == Role.PEDESTRIAN ? TravelMode.WALK
                : request.getVehicleType() != null ? TravelMode.from(request.getVehicleType())
                : owner.getRole() == Role.EMERGENCY ? TravelMode.EMERGENCY : TravelMode.CAR;
        if (mode == TravelMode.EMERGENCY && !mayDriveEmergencyVehicle(owner)) mode = TravelMode.CAR;
        Vehicle vehicle = Vehicle.builder()
                .vehicleCode(request.getVehicleCode())
                .owner(owner)
                .vehicleType(mode.toVehicleType())
                .travelMode(mode)
                .plateNumber(VehicleLabels.cleanPlate(request.getPlateNumber()))
                .status(VehicleStatus.ACTIVE)
                .emergencyStatus(false)
                .isSimulated(false)
                .speedKmh(0.0)
                .directionDegrees(0.0)
                .build();
        return VehicleResponse.from(vehicleRepository.save(vehicle));
    }

    /**
     * Switches how the owner is travelling (walking, bike, car...) and/or
     * sets the number plate shown to others. Walking makes the account a
     * pedestrian, anything else a driver (emergency and admin accounts
     * keep their role).
     */
    public VehicleResponse update(User owner, Long vehicleId, VehicleUpdateRequest request) {
        Vehicle vehicle = getOwnedVehicleOrThrow(vehicleId, owner.getId());
        TravelMode mode = request.getTravelMode();
        if (mode != null) {
            if (mode == TravelMode.EMERGENCY && !mayDriveEmergencyVehicle(owner)) {
                throw ApiException.badRequest("Only emergency service accounts can travel as an emergency vehicle.");
            }
            vehicle.setTravelMode(mode);
            vehicle.setVehicleType(mode.toVehicleType());
            Role role = owner.getRole();
            if (role == Role.DRIVER || role == Role.PEDESTRIAN) {
                Role wanted = mode.onFoot() ? Role.PEDESTRIAN : Role.DRIVER;
                if (role != wanted) {
                    owner.setRole(wanted);
                    userRepository.save(owner);
                }
            }
        }
        if (request.getPlateNumber() != null) {
            vehicle.setPlateNumber(VehicleLabels.cleanPlate(request.getPlateNumber()));
        }
        vehicle = vehicleRepository.save(vehicle);
        webSocketHandler.broadcast("VEHICLE_UPDATE", Map.of("id", vehicle.getId()));
        return VehicleResponse.from(vehicle);
    }

    private static boolean mayDriveEmergencyVehicle(User user) {
        return user.getRole() == Role.EMERGENCY || user.getRole() == Role.ADMIN;
    }

    public List<VehicleResponse> myVehicles(Long ownerId) {
        return vehicleRepository.findByOwnerId(ownerId).stream()
                .map(VehicleResponse::from).collect(Collectors.toList());
    }

    /** Every vehicle with a live position (see LIVE_SECONDS). */
    public List<VehicleResponse> allActive() {
        return activeNear(null, null, null);
    }

    /**
     * Vehicles with a live position - within `radiusMeters` of (lat, lng),
     * nearest first, when a point is given; otherwise all of them.
     */
    public List<VehicleResponse> activeNear(Double lat, Double lng, Double radiusMeters) {
        LocalDateTime now = LocalDateTime.now();
        List<Vehicle> live = vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(v -> v.seenWithin(now, LIVE_SECONDS))
                .collect(Collectors.toList());
        if (lat == null || lng == null) {
            return live.stream().map(VehicleResponse::from).collect(Collectors.toList());
        }
        double radius = clampRadius(radiusMeters, 2000);
        return live.stream()
                .map(v -> Map.entry(GeoUtil.distanceMeters(lat, lng, v.getCurrentLatitude(), v.getCurrentLongitude()), v))
                .filter(e -> e.getKey() <= radius)
                .sorted(Map.Entry.comparingByKey())
                .map(e -> VehicleResponse.from(e.getValue()))
                .collect(Collectors.toList());
    }

    /** A usable radius from what a client asked for: `fallback` if none, never more than MAX_RADIUS_M. */
    public static double clampRadius(Double requested, double fallback) {
        double r = requested == null || requested.isNaN() || requested <= 0 ? fallback : requested;
        return Math.min(MAX_RADIUS_M, Math.max(50, r));
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
        webSocketHandler.broadcast("VEHICLE_UPDATE", Map.of("id", vehicle.getId()));

        // Run risk analysis synchronously for the prototype - simple and
        // easy to demo (every location update produces a fresh risk score).
        riskEngineService.evaluate(vehicle);

        // Automatic traffic-rule checks (speed zones, one-way roads,
        // no-entry zones) - see RuleMonitorService.
        ruleMonitorService.check(vehicle, request, prevLat, prevLng, prevTime);

        // "Pothole 300 m ahead" - reports on the road in front of you.
        hazardAheadService.check(vehicle);

        return response;
    }

    public List<NearbyVehicleResponse> nearby(Vehicle vehicle, double radiusMeters) {
        if (vehicle.getCurrentLatitude() == null || vehicle.getCurrentLongitude() == null) {
            throw ApiException.badRequest("Vehicle has no known location yet");
        }
        LocalDateTime now = LocalDateTime.now();
        return vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(v -> !v.getId().equals(vehicle.getId()))
                .filter(v -> v.seenWithin(now, LIVE_SECONDS))
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
