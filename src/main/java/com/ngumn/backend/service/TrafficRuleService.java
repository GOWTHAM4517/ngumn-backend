package com.ngumn.backend.service;

import com.ngumn.backend.dto.RuleZoneRequest;
import com.ngumn.backend.dto.RuleZoneResponse;
import com.ngumn.backend.dto.ViolationRequest;
import com.ngumn.backend.dto.ViolationResponse;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.RoadSpeedLimitRepository;
import com.ngumn.backend.repository.TrafficRuleZoneRepository;
import com.ngumn.backend.repository.TrafficViolationRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Traffic rules and violations.
 *
 * Zones: speed limits (RoadSpeedLimit) plus one-way roads and no-entry
 * areas (TrafficRuleZone), served as one list - the app's Drive Guard
 * checks them on the phone, and RuleMonitorService checks them on the
 * server for every vehicle sharing its location.
 *
 * Violations: whatever detected it (the server monitor, Drive Guard, or a
 * complaint the community confirmed), a violation is saved here, sent to
 * dashboards over the live socket, and the driver is told through the
 * normal alert feed. The same rule broken by the same vehicle again
 * within two minutes counts once - one long stretch of speeding is one
 * record, not fifty.
 */
@Service
public class TrafficRuleService {

    private static final long REPEAT_WINDOW_SECONDS = 120;
    private static final long ZONE_CACHE_MS = 30_000;

    private final TrafficRuleZoneRepository zoneRepository;
    private final RoadSpeedLimitRepository speedLimitRepository;
    private final TrafficViolationRepository violationRepository;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;

    private volatile List<RuleZoneResponse> zoneCache = null;
    private volatile long zoneCacheAt = 0;

    public TrafficRuleService(TrafficRuleZoneRepository zoneRepository,
                              RoadSpeedLimitRepository speedLimitRepository,
                              TrafficViolationRepository violationRepository,
                              AlertService alertService,
                              NgumnWebSocketHandler webSocketHandler) {
        this.zoneRepository = zoneRepository;
        this.speedLimitRepository = speedLimitRepository;
        this.violationRepository = violationRepository;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
    }

    // ------------------------------------------------------------------
    // Zones
    // ------------------------------------------------------------------

    /** Every active zone. Cached for 30 s because the monitor reads it on every location update. */
    public List<RuleZoneResponse> activeZones() {
        List<RuleZoneResponse> cached = zoneCache;
        if (cached != null && System.currentTimeMillis() - zoneCacheAt < ZONE_CACHE_MS) {
            return cached;
        }
        List<RuleZoneResponse> all = new ArrayList<>();
        for (RoadSpeedLimit s : speedLimitRepository.findAll()) {
            all.add(RuleZoneResponse.fromSpeedLimit(s));
        }
        for (TrafficRuleZone z : zoneRepository.findByActiveTrue()) {
            all.add(RuleZoneResponse.fromZone(z));
        }
        List<RuleZoneResponse> frozen = Collections.unmodifiableList(all);
        zoneCache = frozen;
        zoneCacheAt = System.currentTimeMillis();
        return frozen;
    }

    /** Zones within radiusKm of a point (all zones when no point is given). */
    public List<RuleZoneResponse> zonesNear(Double lat, Double lng, double radiusKm) {
        List<RuleZoneResponse> all = activeZones();
        if (lat == null || lng == null) {
            return all;
        }
        double reach = Math.max(0.5, radiusKm) * 1000;
        List<RuleZoneResponse> near = new ArrayList<>();
        for (RuleZoneResponse z : all) {
            if (gapMeters(z, lat, lng) <= reach) near.add(z);
        }
        return near;
    }

    /** Distance from a point to the edge of a zone - 0 when the point is inside it. */
    public static double gapMeters(RuleZoneResponse z, double lat, double lng) {
        double radius = z.getRadiusMeters() != null ? z.getRadiusMeters() : 0;
        if (z.getEndLatitude() != null && z.getEndLongitude() != null) {
            GeoUtil.SegmentHit hit = GeoUtil.projectOnSegment(lat, lng,
                    z.getLatitude(), z.getLongitude(), z.getEndLatitude(), z.getEndLongitude());
            return Math.max(0, hit.getDistanceMeters() - radius);
        }
        return Math.max(0, GeoUtil.distanceMeters(lat, lng, z.getLatitude(), z.getLongitude()) - radius);
    }

    /** Whether a zone's rule applies to a vehicle type (no types listed = every vehicle). */
    public static boolean appliesTo(RuleZoneResponse z, VehicleType type) {
        if (z.getVehicleTypes() == null || z.getVehicleTypes().isEmpty()) return true;
        return type != null && z.getVehicleTypes().contains(type.name());
    }

    public RuleZoneResponse createZone(RuleZoneRequest request) {
        String kind = request.getKind() == null ? "" : request.getKind().trim().toUpperCase(Locale.ROOT);
        checkCoordinates(request.getLatitude(), request.getLongitude());
        String name = request.getName() != null ? request.getName().trim() : "";

        RuleZoneResponse created = switch (kind) {
            case "SPEED_LIMIT" -> createSpeedZone(request, name);
            case "ONE_WAY" -> createOneWay(request, name);
            case "NO_ENTRY" -> createNoEntry(request, name);
            default -> throw ApiException.badRequest("kind must be SPEED_LIMIT, ONE_WAY or NO_ENTRY");
        };
        zonesChanged();
        return created;
    }

    private RuleZoneResponse createSpeedZone(RuleZoneRequest request, String name) {
        Integer limit = request.getSpeedLimitKmh();
        if (limit == null || limit < 5 || limit > 150) {
            throw ApiException.badRequest("speedLimitKmh must be between 5 and 150");
        }
        RoadSpeedLimit saved = speedLimitRepository.save(RoadSpeedLimit.builder()
                .roadName(name.isEmpty() ? limit + " km/h zone" : name)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .speedLimitKmh(limit)
                .radiusMeters(clamp(request.getRadiusMeters(), 500, 50, 20000))
                .build());
        return RuleZoneResponse.fromSpeedLimit(saved);
    }

    private RuleZoneResponse createOneWay(RuleZoneRequest request, String name) {
        Double endLat = request.getEndLatitude();
        Double endLng = request.getEndLongitude();
        if (endLat == null || endLng == null) {
            throw ApiException.badRequest("A one-way road needs an end point (endLatitude, endLongitude) - "
                    + "traffic may only go from the start towards it");
        }
        checkCoordinates(endLat, endLng);
        double length = GeoUtil.distanceMeters(request.getLatitude(), request.getLongitude(), endLat, endLng);
        if (length < 20 || length > 5000) {
            throw ApiException.badRequest("A one-way road segment must be 20 m to 5 km long");
        }
        TrafficRuleZone saved = zoneRepository.save(TrafficRuleZone.builder()
                .name(name.isEmpty() ? "One-way road" : name)
                .zoneType(RuleZoneType.ONE_WAY)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .endLatitude(endLat)
                .endLongitude(endLng)
                .radiusMeters(clamp(request.getRadiusMeters(), 25, 8, 100))
                .vehicleTypes(joinVehicleTypes(request.getVehicleTypes()))
                .active(true)
                .build());
        return RuleZoneResponse.fromZone(saved);
    }

    private RuleZoneResponse createNoEntry(RuleZoneRequest request, String name) {
        TrafficRuleZone saved = zoneRepository.save(TrafficRuleZone.builder()
                .name(name.isEmpty() ? "No-entry zone" : name)
                .zoneType(RuleZoneType.NO_ENTRY)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .radiusMeters(clamp(request.getRadiusMeters(), 100, 15, 3000))
                .vehicleTypes(joinVehicleTypes(request.getVehicleTypes()))
                .active(true)
                .build());
        return RuleZoneResponse.fromZone(saved);
    }

    public void deleteZone(String kind, Long id) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if ("SPEED_LIMIT".equals(k)) {
            if (!speedLimitRepository.existsById(id)) {
                throw ApiException.notFound("Speed-limit zone not found");
            }
            speedLimitRepository.deleteById(id);
        } else if ("ONE_WAY".equals(k) || "NO_ENTRY".equals(k)) {
            TrafficRuleZone zone = zoneRepository.findById(id)
                    .orElseThrow(() -> ApiException.notFound("Rule zone not found"));
            zoneRepository.delete(zone);
        } else {
            throw ApiException.badRequest("kind must be SPEED_LIMIT, ONE_WAY or NO_ENTRY");
        }
        zonesChanged();
    }

    private void zonesChanged() {
        zoneCache = null;
        webSocketHandler.broadcast("RULE_ZONES", Map.of("changed", true));
    }

    // ------------------------------------------------------------------
    // Violations
    // ------------------------------------------------------------------

    /**
     * Saves a violation and (when alertDriver) tells the driver through
     * the alert feed. A repeat of the same rule by the same vehicle within
     * two minutes returns the earlier record instead of a new one.
     */
    public ViolationResponse record(TrafficViolation draft, boolean alertDriver) {
        boolean simulated = Boolean.TRUE.equals(draft.getSimulated());
        if (draft.getSource() == null) {
            draft.setSource(ViolationSource.MONITOR);
        }
        // Each confirmed complaint is its own incident; only automatic
        // detections are collapsed.
        if (draft.getSource() != ViolationSource.COMMUNITY) {
            Optional<TrafficViolation> last = violationRepository
                    .findTopByVehicleIdAndTypeOrderByCreatedAtDesc(draft.getVehicle().getId(), draft.getType());
            if (last.isPresent()) {
                TrafficViolation prev = last.get();
                boolean recent = prev.getCreatedAt() != null
                        && prev.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(REPEAT_WINDOW_SECONDS));
                if (recent && simulated == Boolean.TRUE.equals(prev.getSimulated())
                        && prev.getSource() != ViolationSource.COMMUNITY) {
                    return ViolationResponse.from(prev);
                }
            }
        }

        if (draft.getMessage() == null || draft.getMessage().isBlank()) {
            draft.setMessage(describe(draft));
        }
        draft.setMessage(truncate(draft.getMessage(), 280));
        draft.setSimulated(simulated);
        TrafficViolation saved = violationRepository.save(draft);
        ViolationResponse response = ViolationResponse.from(saved);
        webSocketHandler.broadcast("VIOLATION", Map.of("id", saved.getId(), "type", saved.getType().name()));

        if (alertDriver && saved.getVehicle() != null) {
            alertService.raise(saved.getVehicle(), saved.getDriver(), alertTypeFor(saved.getType()), riskFor(saved),
                    (simulated ? "Test drive - " : "") + saved.getMessage(),
                    saved.getLatitude(), saved.getLongitude());
        }
        return response;
    }

    /**
     * A violation reported by Drive Guard on the driver's own phone. Real
     * reports are limited to overloading (the phone is the only place that
     * knows how many people are on board); speeding, one-way and no-entry
     * are detected by the server from live location. Test Drive reports
     * (simulated = true) may be of any type.
     */
    public ViolationResponse reportFromDevice(User driver, Vehicle vehicle, ViolationRequest request) {
        checkCoordinates(request.getLatitude(), request.getLongitude());
        boolean simulated = Boolean.TRUE.equals(request.getSimulated());
        ViolationType type = request.getType();

        if (!simulated && type != ViolationType.OVERLOAD) {
            throw ApiException.badRequest("Speed, one-way and no-entry violations are detected automatically from "
                    + "live location - only overloading is reported from the phone");
        }
        if (type == ViolationType.OVERSPEED) {
            if (request.getSpeedKmh() == null || request.getLimitKmh() == null
                    || request.getSpeedKmh() <= request.getLimitKmh()) {
                throw ApiException.badRequest("Over-speeding needs a speedKmh above limitKmh");
            }
        }
        if (type == ViolationType.OVERLOAD) {
            if (request.getOccupants() == null || request.getSeatCapacity() == null
                    || request.getSeatCapacity() < 1 || request.getOccupants() <= request.getSeatCapacity()) {
                throw ApiException.badRequest("Overloading needs more occupants than seatCapacity");
            }
        }

        TrafficViolation draft = TrafficViolation.builder()
                .vehicle(vehicle)
                .driver(driver)
                .type(type)
                .source(ViolationSource.DRIVE_GUARD)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .speedKmh(request.getSpeedKmh())
                .limitKmh(request.getLimitKmh())
                .headingDegrees(request.getHeadingDegrees())
                .occupants(request.getOccupants())
                .seatCapacity(request.getSeatCapacity())
                .zoneName(trimToNull(request.getZoneName()))
                .simulated(simulated)
                .build();
        return record(draft, true);
    }

    public List<ViolationResponse> mine(Long driverId) {
        return violationRepository.findTop100ByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(ViolationResponse::from).collect(Collectors.toList());
    }

    public List<ViolationResponse> recent() {
        return violationRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(ViolationResponse::from).collect(Collectors.toList());
    }

    /** Plain-language summary shown to the driver, e.g. "Over-speeding: 72 km/h in a 50 km/h zone (MG Road)." */
    public static String describe(TrafficViolation v) {
        String where = v.getZoneName() != null && !v.getZoneName().isBlank() ? " (" + v.getZoneName() + ")" : "";
        return switch (v.getType()) {
            case OVERSPEED -> v.getSpeedKmh() != null && v.getLimitKmh() != null
                    ? String.format(Locale.ROOT, "Over-speeding: %d km/h in a %d km/h zone%s. Slow down.",
                        Math.round(v.getSpeedKmh()), v.getLimitKmh(), where)
                    : "Over-speeding" + where + ". Slow down.";
            case WRONG_WAY -> "Wrong-way driving on a one-way road" + where + ". Stop safely and turn back.";
            case NO_ENTRY -> "Entered a no-entry zone" + where + ". Leave the way you came in.";
            case OVERLOAD -> v.getOccupants() != null && v.getSeatCapacity() != null
                    ? String.format(Locale.ROOT, "Overloading: %d people on a vehicle allowed %d.",
                        v.getOccupants(), v.getSeatCapacity())
                    : "Overloading: too many people on board.";
            case SIGNAL_JUMP -> "Jumped a red signal" + where + ".";
            case NO_HELMET -> "Riding without a helmet" + where + ".";
            case NO_SEATBELT -> "Driving without a seat belt" + where + ".";
            case PHONE_USE -> "Using a phone while driving" + where + ".";
            case DANGEROUS_DRIVING -> "Rash or dangerous driving" + where + ".";
            case EMERGENCY_BLOCKING -> "Did not give way to an emergency vehicle" + where + ".";
        };
    }

    /** "...your vehicle was <phrase>" - used when the community confirms a complaint. */
    public static String phrase(ViolationType type) {
        return switch (type) {
            case OVERSPEED -> "over-speeding";
            case WRONG_WAY -> "driving on the wrong side";
            case NO_ENTRY -> "entering a no-entry road";
            case OVERLOAD -> "carrying too many people";
            case SIGNAL_JUMP -> "jumping a red signal";
            case NO_HELMET -> "being ridden without a helmet";
            case NO_SEATBELT -> "being driven without a seat belt";
            case PHONE_USE -> "being driven by someone using a phone";
            case DANGEROUS_DRIVING -> "being driven rashly";
            case EMERGENCY_BLOCKING -> "not giving way to an emergency vehicle";
        };
    }

    private static AlertType alertTypeFor(ViolationType type) {
        return switch (type) {
            case OVERSPEED -> AlertType.OVERSPEED;
            case EMERGENCY_BLOCKING -> AlertType.EMERGENCY_VEHICLE;
            default -> AlertType.HIGH_RISK;
        };
    }

    private static RiskLevel riskFor(TrafficViolation v) {
        return switch (v.getType()) {
            case OVERSPEED -> v.getSpeedKmh() != null && v.getLimitKmh() != null
                    && v.getSpeedKmh() - v.getLimitKmh() >= 20 ? RiskLevel.HIGH : RiskLevel.MEDIUM;
            case WRONG_WAY, SIGNAL_JUMP, PHONE_USE, DANGEROUS_DRIVING, EMERGENCY_BLOCKING -> RiskLevel.HIGH;
            default -> RiskLevel.MEDIUM;
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static void checkCoordinates(Double lat, Double lng) {
        if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw ApiException.badRequest("Invalid coordinates");
        }
    }

    private static double clamp(Double value, double fallback, double min, double max) {
        double v = value != null ? value : fallback;
        return Math.max(min, Math.min(max, v));
    }

    private static String joinVehicleTypes(List<String> types) {
        if (types == null || types.isEmpty()) return null;
        List<String> clean = new ArrayList<>();
        for (String raw : types) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toUpperCase(Locale.ROOT);
            try {
                VehicleType.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown vehicle type: " + raw);
            }
            if (!clean.contains(name)) clean.add(name);
        }
        return clean.isEmpty() ? null : String.join(",", clean);
    }

    static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
