package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.RiskAnalysisRepository;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.RoadSpeedLimitRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.ReportLifetime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prototype risk-analysis engine.
 *
 * This is a transparent, rule-based scoring model - NOT a trained ML
 * model - built from configurable weights so its behaviour can be
 * explained and tuned live in a viva. The weights/thresholds below are
 * demo parameters (see application.properties), not scientifically
 * validated real-world thresholds.
 *
 * A vehicle is scored on every location update (every few seconds), so
 * each kind of alert is raised at most once every 3 minutes per vehicle -
 * otherwise a driver would get a new identical alert every few seconds.
 */
@Service
public class RiskEngineService {

    private static final int DEFAULT_SPEED_LIMIT_KMH = 60;
    private static final long ALERT_REPEAT_MS = 3 * 60_000;
    /** Only vehicles seen in the last 3 minutes count as "nearby". */
    private static final long LIVE_SECONDS = 180;

    private final VehicleRepository vehicleRepository;
    private final RoadReportRepository roadReportRepository;
    private final RoadSpeedLimitRepository roadSpeedLimitRepository;
    private final RiskAnalysisRepository riskAnalysisRepository;
    private final AlertService alertService;
    /** When each (vehicle, alert type) was last raised, for the 3-minute repeat guard. */
    private final Map<String, Long> lastRaised = new ConcurrentHashMap<>();

    @Value("${ngumn.risk.nearby-radius-meters:300}")
    private double nearbyRadiusMeters;

    @Value("${ngumn.risk.overspeed-margin-kmh:10}")
    private double overspeedMarginKmh;

    @Value("${ngumn.risk.high-risk-score:70}")
    private int highRiskScore;

    @Value("${ngumn.risk.medium-risk-score:40}")
    private int mediumRiskScore;

    public RiskEngineService(VehicleRepository vehicleRepository,
                              RoadReportRepository roadReportRepository,
                              RoadSpeedLimitRepository roadSpeedLimitRepository,
                              RiskAnalysisRepository riskAnalysisRepository,
                              AlertService alertService) {
        this.vehicleRepository = vehicleRepository;
        this.roadReportRepository = roadReportRepository;
        this.roadSpeedLimitRepository = roadSpeedLimitRepository;
        this.riskAnalysisRepository = riskAnalysisRepository;
        this.alertService = alertService;
    }

    public RiskAnalysis evaluate(Vehicle vehicle) {
        if (vehicle.getCurrentLatitude() == null || vehicle.getCurrentLongitude() == null) {
            return null;
        }

        StringBuilder factors = new StringBuilder();
        int score = 0;

        // --- 1. Overspeed factor -------------------------------------
        int speedLimit = resolveSpeedLimit(vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        double speed = vehicle.getSpeedKmh() != null ? vehicle.getSpeedKmh() : 0;
        // Speed limits are for motor vehicles - not people walking or cycling.
        boolean overspeed = vehicle.effectiveTravelMode().motorised() && speed > speedLimit + overspeedMarginKmh;
        if (overspeed) {
            int add = 35;
            score += add;
            factors.append(String.format("overspeed(+%d, %.0fkm/h > limit %dkm/h); ", add, speed, speedLimit));
        }

        // --- 2. Nearby vehicles factor ---------------------------------
        List<Vehicle> nearby = findNearbyVehicles(vehicle, nearbyRadiusMeters);
        if (!nearby.isEmpty()) {
            int add = Math.min(25, nearby.size() * 8);
            score += add;
            factors.append(String.format("nearby_vehicles(+%d, count=%d); ", add, nearby.size()));

            boolean emergencyNearby = nearby.stream().anyMatch(v -> Boolean.TRUE.equals(v.getEmergencyStatus()));
            if (emergencyNearby) {
                score += 20;
                factors.append("emergency_vehicle_nearby(+20); ");
            }
        }

        // --- 3. Nearby hazard reports that are still on the road ---------
        // (Expired, cleared and rejected reports don't count - otherwise an
        // old traffic jam would keep raising "hazard nearby" forever.)
        LocalDateTime now = LocalDateTime.now();
        long hazardCount = roadReportRepository
                .findPossiblyActive(ReportStatus.REJECTED, now, now.minus(ReportLifetime.MAX)).stream()
                .filter(r -> r.isActiveAt(now))
                .filter(r -> GeoUtil.distanceMeters(vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude(),
                        r.getLatitude(), r.getLongitude()) <= nearbyRadiusMeters)
                .count();
        if (hazardCount > 0) {
            int add = (int) Math.min(20, hazardCount * 10);
            score += add;
            factors.append(String.format("hazard_reports_nearby(+%d, count=%d); ", add, hazardCount));
        }

        // --- 4. This vehicle itself is an active emergency vehicle -----
        if (Boolean.TRUE.equals(vehicle.getEmergencyStatus())) {
            score += 20;
            factors.append("self_emergency_active(+20); ");
        }

        score = Math.min(100, score);
        RiskLevel level = score >= highRiskScore ? RiskLevel.HIGH
                : score >= mediumRiskScore ? RiskLevel.MEDIUM
                : RiskLevel.LOW;

        RiskAnalysis analysis = RiskAnalysis.builder()
                .vehicle(vehicle)
                .riskScore(score)
                .riskLevel(level)
                .factors(factors.length() == 0 ? "nominal" : factors.toString())
                .build();
        analysis = riskAnalysisRepository.save(analysis);

        boolean emergencyNearby = nearby.stream().anyMatch(v -> Boolean.TRUE.equals(v.getEmergencyStatus()));
        raiseAlertsIfNeeded(vehicle, level, overspeed, speed, speedLimit, nearby.size(), emergencyNearby, hazardCount);

        return analysis;
    }

    /**
     * Alerts in plain words. Hazards on the road ahead get their own,
     * more specific warning ("Pothole 300 m ahead") from HazardAheadService,
     * so they only add to the score here.
     */
    private void raiseAlertsIfNeeded(Vehicle vehicle, RiskLevel level, boolean overspeed, double speed, int speedLimit,
                                      int nearbyCount, boolean emergencyNearby, long hazardCount) {
        if (level == RiskLevel.LOW) {
            return;
        }
        if (overspeed && due(vehicle, AlertType.OVERSPEED)) {
            alertService.raise(vehicle, vehicle.getOwner(), AlertType.OVERSPEED, level,
                    String.format(Locale.ROOT, "You're doing %d km/h where the limit is %d km/h. Slow down.",
                            Math.round(speed), speedLimit),
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        }
        if (level == RiskLevel.HIGH && due(vehicle, AlertType.HIGH_RISK)) {
            alertService.raise(vehicle, vehicle.getOwner(), AlertType.HIGH_RISK, level,
                    highRiskMessage(vehicle.effectiveTravelMode(), overspeed, nearbyCount, emergencyNearby, hazardCount),
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        }
    }

    /**
     * "Take extra care - you're over the speed limit with 3 vehicles close
     * by. Slow down and keep your distance." / "Take extra care - 4 vehicles
     * close by. Keep your distance."
     */
    public static String highRiskMessage(TravelMode mode, boolean overspeed, int nearbyCount, boolean emergencyNearby, long hazardCount) {
        List<String> around = new ArrayList<>();
        if (nearbyCount > 0) around.add(nearbyCount == 1 ? "a vehicle close by" : nearbyCount + " vehicles close by");
        if (emergencyNearby) around.add("an emergency vehicle nearby");
        if (hazardCount > 0) around.add(hazardCount == 1 ? "a reported hazard nearby" : hazardCount + " reported hazards nearby");
        String list = around.isEmpty() ? "" : around.size() == 1 ? around.get(0)
                : String.join(", ", around.subList(0, around.size() - 1)) + " and " + around.get(around.size() - 1);
        String situation;
        if (overspeed) situation = list.isEmpty() ? "you're over the speed limit" : "you're over the speed limit with " + list;
        else situation = list.isEmpty() ? "the road around you is busy" : list;
        String advice = mode.onFoot() ? "Cross carefully and keep to the side of the road."
                : overspeed ? "Slow down and keep your distance." : "Keep your distance.";
        return "Take extra care - " + situation + ". " + advice;
    }

    /** True (and remembered) if this kind of alert wasn't raised for the vehicle in the last 3 minutes. */
    private boolean due(Vehicle vehicle, AlertType type) {
        String key = vehicle.getId() + ":" + type;
        long now = System.currentTimeMillis();
        Long last = lastRaised.get(key);
        if (last != null && now - last < ALERT_REPEAT_MS) {
            return false;
        }
        lastRaised.put(key, now);
        return true;
    }

    /**
     * Other vehicles within `radiusMeters` whose position is live (from the
     * last 3 minutes) - not ones left on the map by people who stopped
     * sharing, and not the same person's other vehicles.
     */
    public List<Vehicle> findNearbyVehicles(Vehicle vehicle, double radiusMeters) {
        LocalDateTime now = LocalDateTime.now();
        Long ownerId = vehicle.getOwner() != null ? vehicle.getOwner().getId() : null;
        return vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(v -> !v.getId().equals(vehicle.getId()))
                .filter(v -> ownerId == null || v.getOwner() == null || !ownerId.equals(v.getOwner().getId()))
                .filter(v -> v.seenWithin(now, LIVE_SECONDS))
                .filter(v -> GeoUtil.distanceMeters(vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude(),
                        v.getCurrentLatitude(), v.getCurrentLongitude()) <= radiusMeters)
                .collect(Collectors.toList());
    }

    private int resolveSpeedLimit(double lat, double lon) {
        return roadSpeedLimitRepository.findAll().stream()
                .filter(l -> GeoUtil.distanceMeters(lat, lon, l.getLatitude(), l.getLongitude()) <= l.getRadiusMeters())
                .findFirst()
                .map(RoadSpeedLimit::getSpeedLimitKmh)
                .orElse(DEFAULT_SPEED_LIMIT_KMH);
    }
}
