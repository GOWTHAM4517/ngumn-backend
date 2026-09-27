package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.VehicleLabels;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warns the drivers AROUND a dangerous vehicle, not just the offender:
 *
 * - When the rule monitor catches a vehicle over-speeding, driving the
 *   wrong way or driving erratically (see RuleMonitorService), every NGUMN
 *   vehicle within 700 m gets a HIGH alert such as "Speeding vehicle
 *   nearby: A car (AP 16 BX 2231) doing 84 km/h, 250 m behind you and
 *   approaching. Stay in your lane and let it pass." (people on foot are
 *   told to keep to the side of the road instead).
 * - When someone reports a rash / speeding / wrong-way driver, vehicles
 *   within 1.5 km of it are told right away - worded as "reported, not
 *   yet verified" - so they can stay alert while the community checks it.
 *
 * Only drivers the danger is heading towards are warned (or anyone within
 * 150 m of it): a speeding car driving away from you isn't a danger to
 * you. When its direction isn't known, everyone in range is warned.
 *
 * Positions are described from each recipient's point of view (ahead,
 * behind, left, right - or a compass direction if they're parked).
 * Each recipient hears about the same vehicle at most once every 3
 * minutes. Only real vehicles are involved: warnings are only about real
 * vehicles (never Demo Mode's simulated traffic), and only drivers whose
 * location is from the last 3 minutes receive them.
 *
 * The app shows these alerts as heads-up banners with a spoken warning:
 * it recognises them by the headline before the first ':' (see
 * HEADLINES), so keep those in sync with ngumn-app/src/lib/alerts-store.tsx.
 */
@Service
public class NearbyDangerService {

    public static final String SPEEDING_NEARBY = "Speeding vehicle nearby";
    public static final String WRONG_WAY_NEARBY = "Wrong-way driver nearby";
    public static final String RASH_NEARBY = "Rash driving nearby";
    public static final String REPORTED_NEARBY = "Rule-breaker reported nearby";
    public static final Set<String> HEADLINES = Set.of(SPEEDING_NEARBY, WRONG_WAY_NEARBY, RASH_NEARBY, REPORTED_NEARBY);

    private static final double VIOLATION_RADIUS_M = 700;
    private static final double REPORT_RADIUS_M = 1500;
    /** This close, a driver is warned whichever way the danger is moving. */
    private static final double CLOSE_M = 150;
    /** Heading within this many degrees of the line to a driver = coming towards them. */
    private static final double APPROACH_DEGREES = 50;
    private static final long REPEAT_MS = 3 * 60_000;
    private static final long FRESH_LOCATION_SECONDS = 180;
    private static final Set<ViolationType> REPORT_TYPES_TO_SHARE = Set.of(
            ViolationType.OVERSPEED, ViolationType.WRONG_WAY, ViolationType.DANGEROUS_DRIVING,
            ViolationType.SIGNAL_JUMP, ViolationType.PHONE_USE);

    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final Map<String, Long> lastWarned = new ConcurrentHashMap<>();

    public NearbyDangerService(VehicleRepository vehicleRepository, AlertService alertService) {
        this.vehicleRepository = vehicleRepository;
        this.alertService = alertService;
    }

    /**
     * The monitor caught `offender` doing something that endangers others:
     * OVERSPEED, WRONG_WAY or DANGEROUS_DRIVING (erratic braking and
     * acceleration). Returns how many drivers were warned.
     */
    public int warnAboutViolation(Vehicle offender, ViolationType type, double speedKmh, Double offenderHeading) {
        if (type != ViolationType.OVERSPEED && type != ViolationType.WRONG_WAY
                && type != ViolationType.DANGEROUS_DRIVING) return 0;
        if (Boolean.TRUE.equals(offender.getIsSimulated())) return 0; // real warnings only

        Double lat = offender.getCurrentLatitude();
        Double lng = offender.getCurrentLongitude();
        if (lat == null || lng == null) return 0;

        int warned = 0;
        for (Vehicle v : vehicleRepository.findByStatus(VehicleStatus.ACTIVE)) {
            if (!canReceive(v) || v.getId().equals(offender.getId())) continue;
            if (sameOwner(v, offender)) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lng);
            if (d > VIOLATION_RADIUS_M) continue;
            if (d > CLOSE_M && !headingTowards(offenderHeading, lat, lng, v)) continue;
            if (!firstInWindow(v.getId() + ":" + offender.getId() + ":" + type)) continue;

            String where = describe(v, lat, lng, d, offenderHeading);
            String who = VehicleLabels.describeCapitalised(offender);
            boolean onFoot = v.effectiveTravelMode().onFoot();
            String message = switch (type) {
                case OVERSPEED -> String.format(Locale.ROOT, "%s: %s doing %d km/h, %s. %s",
                        SPEEDING_NEARBY, who, Math.round(speedKmh), where,
                        onFoot ? "Keep to the side of the road." : "Stay in your lane and let it pass.");
                case WRONG_WAY -> String.format(Locale.ROOT, "%s: %s is driving against traffic, %s. %s",
                        WRONG_WAY_NEARBY, who, where,
                        onFoot ? "Look both ways before you cross." : "Slow down and keep left.");
                default -> String.format(Locale.ROOT, "%s: %s is braking and speeding up sharply, %s. Keep your distance.",
                        RASH_NEARBY, who, where);
            };
            alertService.raise(v, v.getOwner(), AlertType.HIGH_RISK, RiskLevel.HIGH,
                    TrafficRuleService.truncate(message, 290), lat, lng);
            warned++;
        }
        return warned;
    }

    /**
     * Tells the driver the monitor saw braking and speeding up sharply
     * again and again. Not recorded as a violation - GPS speed alone can't
     * prove rash driving - just a nudge, at most once every 3 minutes.
     */
    public boolean cautionErraticDriver(Vehicle vehicle) {
        if (Boolean.TRUE.equals(vehicle.getIsSimulated()) || vehicle.getOwner() == null) return false;
        if (!firstInWindow(vehicle.getId() + ":self:erratic")) return false;
        alertService.raise(vehicle, vehicle.getOwner(), AlertType.HIGH_RISK, RiskLevel.MEDIUM,
                "Rash driving: you braked and sped up sharply several times in the last minute. "
                        + "Drivers around you have been told to keep their distance - please drive smoothly.",
                vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        return true;
    }

    /**
     * Someone reported a rule-breaker. Tell drivers near it straight away
     * (the report is still unverified, and the message says so).
     */
    public int warnAboutReport(ViolationComplaint complaint) {
        if (!REPORT_TYPES_TO_SHARE.contains(complaint.getType())) return 0;
        Vehicle accused = complaint.getAccusedVehicle();
        double lat = complaint.getLatitude();
        double lng = complaint.getLongitude();
        Double heading = null;
        // Use where the reported NGUMN vehicle is now, if we know it.
        if (accused != null && isFresh(accused)) {
            lat = accused.getCurrentLatitude();
            lng = accused.getCurrentLongitude();
            heading = accused.getSpeedKmh() != null && accused.getSpeedKmh() >= 10 ? accused.getDirectionDegrees() : null;
        }
        String plate = VehicleLabels.cleanPlate(complaint.getPlateNumber());
        String who = accused != null ? VehicleLabels.describeCapitalised(accused)
                : plate != null ? "A vehicle (" + plate + ")" : "A vehicle";
        String what = switch (complaint.getType()) {
            case OVERSPEED -> "reported speeding";
            case WRONG_WAY -> "reported driving on the wrong side";
            case SIGNAL_JUMP -> "reported jumping red signals";
            case PHONE_USE -> "reported driving while on the phone";
            default -> "reported driving rashly";
        };
        Long reporterId = complaint.getReporter() != null ? complaint.getReporter().getId() : null;

        int warned = 0;
        for (Vehicle v : vehicleRepository.findByStatus(VehicleStatus.ACTIVE)) {
            if (!canReceive(v)) continue;
            if (accused != null && (v.getId().equals(accused.getId()) || sameOwner(v, accused))) continue;
            if (reporterId != null && v.getOwner() != null && reporterId.equals(v.getOwner().getId())) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lng);
            if (d > REPORT_RADIUS_M) continue;
            if (d > CLOSE_M && !headingTowards(heading, lat, lng, v)) continue;
            if (!firstInWindow(v.getId() + ":complaint:" + complaint.getId())) continue;

            String message = String.format(Locale.ROOT, "%s: %s %s, %s - not verified yet. Stay alert.",
                    REPORTED_NEARBY, who, what, describe(v, lat, lng, d, heading));
            alertService.raise(v, v.getOwner(), AlertType.HIGH_RISK, RiskLevel.MEDIUM,
                    TrafficRuleService.truncate(message, 290), lat, lng);
            warned++;
        }
        return warned;
    }

    // ------------------------------------------------------------------

    private boolean canReceive(Vehicle v) {
        return !Boolean.TRUE.equals(v.getIsSimulated()) && v.getOwner() != null && isFresh(v);
    }

    private static boolean isFresh(Vehicle v) {
        return v.getCurrentLatitude() != null && v.getCurrentLongitude() != null && v.getLastLocationUpdate() != null
                && Duration.between(v.getLastLocationUpdate(), LocalDateTime.now()).getSeconds() <= FRESH_LOCATION_SECONDS;
    }

    /** True if a danger at (lat, lng) moving along `heading` is coming towards `recipient` (or its direction is unknown). */
    static boolean headingTowards(Double heading, double lat, double lng, Vehicle recipient) {
        if (heading == null) return true;
        double toRecipient = GeoUtil.bearingDegrees(lat, lng, recipient.getCurrentLatitude(), recipient.getCurrentLongitude());
        return GeoUtil.angleDifference(heading, toRecipient) <= APPROACH_DEGREES;
    }

    private static boolean sameOwner(Vehicle a, Vehicle b) {
        return a.getOwner() != null && b.getOwner() != null && a.getOwner().getId().equals(b.getOwner().getId());
    }

    private boolean firstInWindow(String key) {
        long now = System.currentTimeMillis();
        Long last = lastWarned.get(key);
        if (last != null && now - last < REPEAT_MS) return false;
        lastWarned.put(key, now);
        if (lastWarned.size() > 5000) lastWarned.values().removeIf(t -> now - t > REPEAT_MS);
        return true;
    }

    /**
     * "250 m behind you and approaching", "1.2 km ahead", "80 m to your
     * left", or "400 m to the north-east" when the recipient is parked.
     */
    static String describe(Vehicle recipient, double lat, double lng, double distanceM, Double offenderHeading) {
        String dist = distanceM < 1000 ? Math.max(10, Math.round(distanceM / 10.0) * 10) + " m"
                : String.format(Locale.ROOT, "%.1f km", distanceM / 1000);
        double toOffender = GeoUtil.bearingDegrees(recipient.getCurrentLatitude(), recipient.getCurrentLongitude(), lat, lng);
        // A stored direction of exactly 0 usually means "phone didn't know"
        // (VehicleService stores 0 then), so only trust a non-zero one.
        boolean moving = recipient.getSpeedKmh() != null && recipient.getSpeedKmh() >= 8
                && recipient.getDirectionDegrees() != null && recipient.getDirectionDegrees() != 0.0;
        String where;
        if (distanceM < 25) {
            where = "right next to you";
        } else if (moving) {
            double rel = ((toOffender - recipient.getDirectionDegrees()) % 360 + 360) % 360;
            if (rel <= 45 || rel >= 315) where = dist + " ahead of you";
            else if (rel >= 135 && rel <= 225) where = dist + " behind you";
            else if (rel < 180) where = dist + " to your right";
            else where = dist + " to your left";
        } else {
            where = dist + " to the " + compass(toOffender);
        }
        if (offenderHeading != null && distanceM >= 25 && headingTowards(offenderHeading, lat, lng, recipient)) {
            where += " and approaching";
        }
        return where;
    }

    private static String compass(double bearing) {
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(((bearing % 360) + 360) % 360 / 45.0) % 8];
    }
}
