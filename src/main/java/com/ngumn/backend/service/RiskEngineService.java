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
import java.util.List;
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
        boolean overspeed = speed > speedLimit + overspeedMarginKmh;
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

        raiseAlertsIfNeeded(vehicle, level, overspeed, !nearby.isEmpty(), hazardCount > 0);

        return analysis;
    }

    private void raiseAlertsIfNeeded(Vehicle vehicle, RiskLevel level, boolean overspeed,
                                      boolean hasNearby, boolean hasHazard) {
        if (level == RiskLevel.LOW) {
            return;
        }
        if (overspeed && due(vehicle, AlertType.OVERSPEED)) {
            alertService.raise(vehicle, vehicle.getOwner(), AlertType.OVERSPEED, level,
                    "Vehicle " + vehicle.getVehicleCode() + " is over the configured speed limit.",
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        }
        if (hasHazard && due(vehicle, AlertType.NEARBY_HAZARD)) {
            alertService.raise(vehicle, vehicle.getOwner(), AlertType.NEARBY_HAZARD, level,
                    "Road hazard reported near vehicle " + vehicle.getVehicleCode() + ".",
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        }
        if (level == RiskLevel.HIGH && due(vehicle, AlertType.HIGH_RISK)) {
            alertService.raise(vehicle, vehicle.getOwner(), AlertType.HIGH_RISK, level,
                    "High risk condition detected for vehicle " + vehicle.getVehicleCode() + ".",
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        }
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

    public List<Vehicle> findNearbyVehicles(Vehicle vehicle, double radiusMeters) {
        return vehicleRepository.findByStatus(VehicleStatus.ACTIVE).stream()
                .filter(v -> !v.getId().equals(vehicle.getId()))
                .filter(v -> v.getCurrentLatitude() != null && v.getCurrentLongitude() != null)
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
