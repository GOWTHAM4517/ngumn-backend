package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.LiveRecipients;
import com.ngumn.backend.util.NotificationPrefs;
import com.ngumn.backend.util.VehicleLabels;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the people AROUND a rule-breaker, not just the offender - the
 * moment it happens, so nobody has to open the app to find out:
 *
 * - When the rule monitor catches a vehicle over-speeding, driving the
 *   wrong way, driving erratically or entering a no-entry zone (see
 *   RuleMonitorService), everyone sharing their location within their
 *   chosen radius (Settings > Notifications, 2 km unless changed) hears
 *   about it. People it's coming towards (within 700 m) get a HIGH
 *   warning such as "Speeding vehicle nearby: A car (AP 16 BX 2231) doing
 *   84 km/h, 250 m behind you and approaching. Stay in your lane and let
 *   it pass." (people on foot are told to keep to the side of the road
 *   instead). Everyone else in range gets a calmer note: "Rule broken
 *   nearby: A car speeding at 95 km/h, 1.2 km to the north-east, heading
 *   away."
 * - When someone reports a rule-breaker (any rule), people within their
 *   radius are told right away - worded as "reported, not yet verified" -
 *   so they can stay alert while the community checks it. Dangerous
 *   driving coming their way (within 1.5 km) is a warning ("Rule-breaker
 *   reported nearby: ... Stay alert."); everyone else gets a calm note
 *   with its own headline ("Rule-breaker reported in your area: ..."), so
 *   older app versions - which pop up and speak every "Rule-breaker
 *   reported nearby" - don't treat these notes as warnings.
 *
 * The warnings about a danger coming your way are always sent; the calmer
 * "in your area" notes follow each person's settings (radius, rule-breakers
 * on/off - see NotificationPrefs). When a vehicle's direction isn't known,
 * everyone close enough is warned as if it were coming their way.
 *
 * Positions are described from each recipient's point of view (ahead,
 * behind, left, right - or a compass direction if they're parked). Each
 * person hears a warning about the same vehicle at most once every 3
 * minutes, and the calmer note at most once every 10. Only real vehicles
 * are involved: never Demo Mode's simulated traffic, and only people whose
 * location is from the last 3 minutes are told.
 *
 * The app shows these alerts as heads-up banners with a spoken warning
 * (and as phone notifications when it's in the background): it recognises
 * them by the headline before the first ':' (see HEADLINES), so keep those
 * in sync with ngumn-app/src/lib/alert-text.ts.
 */
@Service
public class NearbyDangerService {

    public static final String SPEEDING_NEARBY = "Speeding vehicle nearby";
    public static final String WRONG_WAY_NEARBY = "Wrong-way driver nearby";
    public static final String RASH_NEARBY = "Rash driving nearby";
    /** A reported dangerous driver coming your way. */
    public static final String REPORTED_NEARBY = "Rule-breaker reported nearby";
    /** A reported rule-breaker somewhere in your area, not coming your way. */
    public static final String REPORTED_IN_AREA = "Rule-breaker reported in your area";
    /** A rule broken somewhere in your area, by a vehicle that isn't coming your way. */
    public static final String RULE_BROKEN_NEARBY = "Rule broken nearby";
    public static final Set<String> HEADLINES = Set.of(SPEEDING_NEARBY, WRONG_WAY_NEARBY, RASH_NEARBY,
            REPORTED_NEARBY, REPORTED_IN_AREA, RULE_BROKEN_NEARBY);

    private static final double VIOLATION_RADIUS_M = 700;
    private static final double REPORT_RADIUS_M = 1500;
    /** This close, a driver is warned whichever way the danger is moving. */
    private static final double CLOSE_M = 150;
    /** Heading within this many degrees of the line to a driver = coming towards them. */
    private static final double APPROACH_DEGREES = 50;
    private static final long REPEAT_MS = 3 * 60_000;
    /** The calmer "in your area" note about the same vehicle comes at most this often. */
    private static final long AREA_REPEAT_MS = 10 * 60_000;
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
     * The monitor caught `offender` breaking a rule: OVERSPEED, WRONG_WAY,
     * DANGEROUS_DRIVING (erratic braking and acceleration) or NO_ENTRY.
     * Returns how many people were told.
     */
    public int warnAboutViolation(Vehicle offender, ViolationType type, double speedKmh, Double offenderHeading) {
        if (type != ViolationType.OVERSPEED && type != ViolationType.WRONG_WAY
                && type != ViolationType.DANGEROUS_DRIVING && type != ViolationType.NO_ENTRY) return 0;
        if (Boolean.TRUE.equals(offender.getIsSimulated())) return 0; // real warnings only

        Double lat = offender.getCurrentLatitude();
        Double lng = offender.getCurrentLongitude();
        if (lat == null || lng == null) return 0;
        String who = VehicleLabels.describeCapitalised(offender);

        int told = 0;
        for (Vehicle v : LiveRecipients.latestPerOwner(vehicleRepository.findByStatus(VehicleStatus.ACTIVE), this::canReceive)) {
            if (v.getId().equals(offender.getId()) || sameOwner(v, offender)) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lng);
            String areaKey = "u" + v.getOwner().getId() + ":area:" + offender.getId() + ":" + type;
            boolean comingTowards = type != ViolationType.NO_ENTRY && d <= VIOLATION_RADIUS_M
                    && (d <= CLOSE_M || headingTowards(offenderHeading, lat, lng, v));
            if (comingTowards) {
                if (!firstInWindow(v.getId() + ":" + offender.getId() + ":" + type, REPEAT_MS)) continue;
                firstInWindow(areaKey, AREA_REPEAT_MS); // they know - no calmer note on top
                String where = describe(v, lat, lng, d, offenderHeading);
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
                told++;
                continue;
            }
            // Not coming their way: a calmer note, if they want to hear about their area.
            User owner = v.getOwner();
            if (!NotificationPrefs.ruleBreakers(owner) || d > NotificationPrefs.radiusMeters(owner)) continue;
            if (!firstInWindow(areaKey, AREA_REPEAT_MS)) continue;
            String what = switch (type) {
                case OVERSPEED -> String.format(Locale.ROOT, "speeding at %d km/h", Math.round(speedKmh));
                case WRONG_WAY -> "going the wrong way on a one-way road";
                case NO_ENTRY -> "driving into a no-entry zone";
                default -> "driving rashly - braking and speeding up sharply";
            };
            boolean away = offenderHeading != null && d >= 25 && !headingTowards(offenderHeading, lat, lng, v);
            String message = String.format(Locale.ROOT, "%s: %s %s, %s%s.",
                    RULE_BROKEN_NEARBY, who, what, describe(v, lat, lng, d, offenderHeading), away ? ", heading away" : "");
            alertService.raise(v, owner, AlertType.HIGH_RISK, type == ViolationType.NO_ENTRY ? RiskLevel.LOW : RiskLevel.MEDIUM,
                    TrafficRuleService.truncate(message, 290), lat, lng);
            told++;
        }
        return told;
    }

    /**
     * Tells the driver the monitor saw braking and speeding up sharply
     * again and again. Not recorded as a violation - GPS speed alone can't
     * prove rash driving - just a nudge, at most once every 3 minutes.
     */
    public boolean cautionErraticDriver(Vehicle vehicle) {
        if (Boolean.TRUE.equals(vehicle.getIsSimulated()) || vehicle.getOwner() == null) return false;
        if (!firstInWindow(vehicle.getId() + ":self:erratic", REPEAT_MS)) return false;
        alertService.raise(vehicle, vehicle.getOwner(), AlertType.HIGH_RISK, RiskLevel.MEDIUM,
                "Rash driving: you braked and sped up sharply several times in the last minute. "
                        + "Drivers around you have been told to keep their distance - please drive smoothly.",
                vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude());
        return true;
    }

    /**
     * Someone reported a rule-breaker. Tell the people around it straight
     * away (the report is still unverified, and the message says so).
     * Returns how many people were told.
     */
    public int warnAboutReport(ViolationComplaint complaint) {
        if (complaint.getType() == null) return 0;
        Vehicle accused = complaint.getAccusedVehicle();
        double lat = complaint.getLatitude();
        double lng = complaint.getLongitude();
        Double heading = null;
        // Use where the reported Raksio vehicle is now, if we know it.
        if (accused != null && isFresh(accused)) {
            lat = accused.getCurrentLatitude();
            lng = accused.getCurrentLongitude();
            heading = accused.getSpeedKmh() != null && accused.getSpeedKmh() >= 10 ? accused.getDirectionDegrees() : null;
        }
        String plate = VehicleLabels.cleanPlate(complaint.getPlateNumber());
        String who = accused != null ? VehicleLabels.describeCapitalised(accused)
                : plate != null ? "A vehicle (" + plate + ")" : "A vehicle";
        String what = reportedWhat(complaint.getType());
        boolean dangerous = REPORT_TYPES_TO_SHARE.contains(complaint.getType());
        Long reporterId = complaint.getReporter() != null ? complaint.getReporter().getId() : null;

        int told = 0;
        for (Vehicle v : LiveRecipients.latestPerOwner(vehicleRepository.findByStatus(VehicleStatus.ACTIVE), this::canReceive)) {
            if (accused != null && (v.getId().equals(accused.getId()) || sameOwner(v, accused))) continue;
            if (reporterId != null && reporterId.equals(v.getOwner().getId())) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lng);
            // Dangerous driving coming their way: always told. Anything else: if they want to hear about their area.
            boolean comingTowards = dangerous && d <= REPORT_RADIUS_M && (d <= CLOSE_M || headingTowards(heading, lat, lng, v));
            User owner = v.getOwner();
            boolean inArea = NotificationPrefs.ruleBreakers(owner) && d <= NotificationPrefs.radiusMeters(owner);
            if (!comingTowards && !inArea) continue;
            if (!firstInWindow("u" + owner.getId() + ":complaint:" + complaint.getId(), REPEAT_MS)) continue;

            String message = String.format(Locale.ROOT, "%s: %s %s, %s - not verified yet.%s",
                    comingTowards ? REPORTED_NEARBY : REPORTED_IN_AREA, who, what, describe(v, lat, lng, d, heading),
                    comingTowards ? " Stay alert." : "");
            alertService.raise(v, owner, AlertType.HIGH_RISK, comingTowards ? RiskLevel.MEDIUM : RiskLevel.LOW,
                    TrafficRuleService.truncate(message, 290), lat, lng, null, complaint.getId());
            told++;
        }
        return told;
    }

    /** "reported speeding", "reported riding without a helmet"... */
    static String reportedWhat(ViolationType type) {
        return switch (type) {
            case OVERSPEED -> "reported speeding";
            case WRONG_WAY -> "reported driving on the wrong side";
            case NO_ENTRY -> "reported driving into a no-entry road";
            case OVERLOAD -> "reported carrying too many people";
            case SIGNAL_JUMP -> "reported jumping red signals";
            case NO_HELMET -> "reported riding without a helmet";
            case NO_SEATBELT -> "reported driving without a seat belt";
            case PHONE_USE -> "reported driving while on the phone";
            case EMERGENCY_BLOCKING -> "reported blocking an emergency vehicle";
            default -> "reported driving rashly";
        };
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

    private boolean firstInWindow(String key, long windowMs) {
        long now = System.currentTimeMillis();
        Long last = lastWarned.get(key);
        if (last != null && now - last < windowMs) return false;
        lastWarned.put(key, now);
        if (lastWarned.size() > 5000) lastWarned.values().removeIf(t -> now - t > AREA_REPEAT_MS);
        return true;
    }

    /**
     * "250 m behind you and approaching", "1.2 km ahead", "80 m to your
     * left", or "400 m to the north-east" when the recipient is parked.
     */
    static String describe(Vehicle recipient, double lat, double lng, double distanceM, Double offenderHeading) {
        // Rounded to 10 m; from 995 m it reads "1.0 km", never "1000 m".
        long roundedM = Math.max(10, Math.round(distanceM / 10.0) * 10);
        String dist = roundedM < 1000 ? roundedM + " m" : String.format(Locale.ROOT, "%.1f km", distanceM / 1000);
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
