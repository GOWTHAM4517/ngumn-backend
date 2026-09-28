package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.LiveRecipients;
import com.ngumn.backend.util.NotificationPrefs;
import com.ngumn.backend.util.ReportLifetime;
import com.ngumn.backend.util.VehicleLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hazard reports reach the people around them the moment they're made -
 * nobody has to open the app and go looking.
 *
 * - When someone reports a hazard, everyone sharing their location within
 *   their chosen radius (Settings > Notifications, 2 km unless changed) is
 *   told straight away. People heading into it (or right next to it, or
 *   stopped within 300 m) get the safety warning: "Hazard ahead: Pothole,
 *   300 m ahead of you - reported just now. Slow down." Everyone else in
 *   range gets a calmer note: "Hazard reported nearby: Pothole, 1.2 km to
 *   the north-east - reported just now. Take care if you go that way."
 * - As people move (every location update), a report on the road in front
 *   of them - within 800 m and within 45 degrees of their direction of
 *   travel - gets the "Hazard ahead" warning before they reach it, even if
 *   they already had the calmer note.
 *
 * Each person hears about the same report at most once every 20 minutes
 * as a warning (and gets the "in your area" note once), never about their
 * own reports, and only while their position is live. Reports that have
 * cleared (expired, taken down or rejected) never warn anyone. Someone who
 * turned hazard notifications off still gets the warnings for hazards in
 * their path. The app recognises these alerts by their headline ("Hazard
 * ahead" / "Hazard nearby" / "Hazard reported nearby") - keep in sync with
 * ngumn-app/src/lib/alerts-store.tsx.
 */
@Service
public class HazardAheadService {

    public static final String HEADLINE_AHEAD = "Hazard ahead";
    public static final String HEADLINE_NEARBY = "Hazard nearby";
    /** A new report somewhere in your area, not in your path. */
    public static final String HEADLINE_REPORTED = "Hazard reported nearby";

    /** How far ahead on the road a report is worth a warning. */
    static final double AHEAD_M = 800;
    /** This close, warn whichever way someone is going. */
    static final double CLOSE_M = 150;
    /** Stopped (or walking slowly): warn about reports this close. */
    static final double STOPPED_M = 300;
    /** "Ahead" = within this many degrees of the direction of travel. */
    static final double AHEAD_DEGREES = 45;
    static final double MOVING_KMH = 8;
    static final long REPEAT_MS = 20 * 60_000;
    /** The "in your area" note about a new report comes once. */
    static final long AREA_REPEAT_MS = 12 * 60 * 60_000L;
    static final long LIVE_SECONDS = 180;
    private static final long CACHE_MS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(HazardAheadService.class);

    private final RoadReportRepository roadReportRepository;
    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final Map<String, Long> lastWarned = new ConcurrentHashMap<>();
    private final Map<String, Long> areaNoticed = new ConcurrentHashMap<>();
    private volatile List<RoadReport> cached = List.of();
    private volatile long cachedAt = 0;

    public HazardAheadService(RoadReportRepository roadReportRepository, VehicleRepository vehicleRepository,
                              AlertService alertService) {
        this.roadReportRepository = roadReportRepository;
        this.vehicleRepository = vehicleRepository;
        this.alertService = alertService;
    }

    /** Re-read the reports on the next check (e.g. after one was added or taken down). */
    public void refreshSoon() {
        cachedAt = 0;
    }

    /**
     * A hazard was just reported: tell everyone around it - a warning for
     * the people heading into it, a note for everyone else within their
     * radius. Returns how many people were told.
     */
    public int warnAboutNewReport(RoadReport report) {
        try {
            refreshSoon(); // the new report must be in the next check's list
            if (report == null || report.getLatitude() == null || report.getLongitude() == null) return 0;
            LocalDateTime now = LocalDateTime.now();
            if (!report.isActiveAt(now)) return 0;
            int told = 0;
            for (Vehicle v : LiveRecipients.latestPerOwner(vehicleRepository.findByStatus(VehicleStatus.ACTIVE),
                    x -> canReceive(x, now))) {
                if (isReporter(v, report)) continue;
                double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(),
                        report.getLatitude(), report.getLongitude());
                if (relevant(v, report, d)) {
                    // In their path: the safety warning, whatever their settings.
                    if (warnIfRelevant(v, report, now)) {
                        firstAreaNotice(v, report); // no second, calmer note about it
                        told++;
                    }
                } else if (tellAboutNewReport(v, report, d, now)) {
                    told++;
                }
            }
            return told;
        } catch (RuntimeException e) {
            log.warn("Couldn't send hazard warnings for report {}: {}", report != null ? report.getId() : null, e.getMessage());
            return 0;
        }
    }

    /**
     * Called on every location update: warns about the nearest report on the
     * road ahead that this person hasn't been told about recently.
     * Returns true if a warning was sent.
     */
    public boolean check(Vehicle v) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (!canReceive(v, now)) return false;
            RoadReport nearest = null;
            double nearestD = Double.MAX_VALUE;
            for (RoadReport r : activeReports(now)) {
                if (r.getLatitude() == null || r.getLongitude() == null || isReporter(v, r)) continue;
                double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), r.getLatitude(), r.getLongitude());
                if (d >= nearestD || !relevant(v, r, d) || recentlyWarned(v, r)) continue;
                nearest = r;
                nearestD = d;
            }
            return nearest != null && warnIfRelevant(v, nearest, now);
        } catch (RuntimeException e) {
            // A warning must never break a location update.
            log.warn("Hazard check skipped for vehicle {}: {}", v != null ? v.getId() : null, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------

    private boolean warnIfRelevant(Vehicle v, RoadReport r, LocalDateTime now) {
        double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), r.getLatitude(), r.getLongitude());
        if (!relevant(v, r, d) || !firstInWindow(v, r)) return false;
        boolean ahead = moving(v) && d > 25;
        String where = NearbyDangerService.describe(v, r.getLatitude(), r.getLongitude(), d, null);
        String message = (ahead ? HEADLINE_AHEAD : HEADLINE_NEARBY) + ": " + label(r) + ", " + where
                + " - reported " + ago(r.getTimestamp(), now) + confirmed(r) + ". " + advice(r.getType(), v.effectiveTravelMode());
        RiskLevel level = r.getType() == ReportType.ACCIDENT || r.getType() == ReportType.EMERGENCY ? RiskLevel.HIGH : RiskLevel.MEDIUM;
        alertService.raise(v, v.getOwner(), AlertType.NEARBY_HAZARD, level,
                TrafficRuleService.truncate(message, 290), r.getLatitude(), r.getLongitude(), r.getId(), null);
        return true;
    }

    /**
     * "Hazard reported nearby: Pothole, 1.2 km to the north-east - reported
     * just now. Take care if you go that way." - for people within their
     * radius who aren't heading into it (and haven't turned these off).
     */
    private boolean tellAboutNewReport(Vehicle v, RoadReport r, double d, LocalDateTime now) {
        User owner = v.getOwner();
        if (!NotificationPrefs.hazards(owner) || d > NotificationPrefs.radiusMeters(owner)) return false;
        if (!firstAreaNotice(v, r)) return false;
        String where = NearbyDangerService.describe(v, r.getLatitude(), r.getLongitude(), d, null);
        String message = HEADLINE_REPORTED + ": " + label(r) + ", " + where
                + " - reported " + ago(r.getTimestamp(), now) + confirmed(r) + ". " + adviceIfPassing(r.getType());
        RiskLevel level = r.getType() == ReportType.ACCIDENT || r.getType() == ReportType.EMERGENCY ? RiskLevel.MEDIUM : RiskLevel.LOW;
        alertService.raise(v, owner, AlertType.NEARBY_HAZARD, level,
                TrafficRuleService.truncate(message, 290), r.getLatitude(), r.getLongitude(), r.getId(), null);
        return true;
    }

    /** True the first time this person is told about report `r` "in their area" (and remembers it). */
    private boolean firstAreaNotice(Vehicle v, RoadReport r) {
        String key = v.getOwner().getId() + ":" + r.getId();
        long now = System.currentTimeMillis();
        Long last = areaNoticed.get(key);
        if (last != null && now - last < AREA_REPEAT_MS) return false;
        areaNoticed.put(key, now);
        if (areaNoticed.size() > 5000) areaNoticed.values().removeIf(t -> now - t > AREA_REPEAT_MS);
        return true;
    }

    /** Is report `r`, `d` metres away, something `v` is about to meet? */
    static boolean relevant(Vehicle v, RoadReport r, double d) {
        if (d > AHEAD_M) return false;
        if (d <= CLOSE_M) return true;
        if (!moving(v)) return d <= STOPPED_M;
        double toHazard = GeoUtil.bearingDegrees(v.getCurrentLatitude(), v.getCurrentLongitude(), r.getLatitude(), r.getLongitude());
        return GeoUtil.angleDifference(v.getDirectionDegrees(), toHazard) <= AHEAD_DEGREES;
    }

    /** Moving with a known direction (a stored 0 usually means "unknown" - see VehicleService). */
    private static boolean moving(Vehicle v) {
        return v.getSpeedKmh() != null && v.getSpeedKmh() >= MOVING_KMH
                && v.getDirectionDegrees() != null && v.getDirectionDegrees() != 0.0;
    }

    private static boolean canReceive(Vehicle v, LocalDateTime now) {
        return v != null && !Boolean.TRUE.equals(v.getIsSimulated()) && v.getOwner() != null && v.seenWithin(now, LIVE_SECONDS);
    }

    private static boolean isReporter(Vehicle v, RoadReport r) {
        return r.getReporter() != null && v.getOwner() != null && r.getReporter().getId() != null
                && r.getReporter().getId().equals(v.getOwner().getId());
    }

    private boolean recentlyWarned(Vehicle v, RoadReport r) {
        Long last = lastWarned.get(v.getId() + ":" + r.getId());
        return last != null && System.currentTimeMillis() - last < REPEAT_MS;
    }

    private boolean firstInWindow(Vehicle v, RoadReport r) {
        if (recentlyWarned(v, r)) return false;
        long now = System.currentTimeMillis();
        lastWarned.put(v.getId() + ":" + r.getId(), now);
        if (lastWarned.size() > 5000) lastWarned.values().removeIf(t -> now - t > REPEAT_MS);
        return true;
    }

    /** Reports still on the road, re-read at most every 10 s (every location update asks). */
    private List<RoadReport> activeReports(LocalDateTime now) {
        long t = System.currentTimeMillis();
        if (t - cachedAt > CACHE_MS) {
            cached = roadReportRepository.findPossiblyActive(ReportStatus.REJECTED, now, now.minus(ReportLifetime.MAX));
            cachedAt = t;
        }
        return cached.stream().filter(r -> r.isActiveAt(now)).toList();
    }

    /** "Pothole", "Animal on road", "Road work / lane closed" - the reporter's short text when there is one. */
    static String label(RoadReport r) {
        String type = switch (r.getType() != null ? r.getType() : ReportType.OTHER) {
            case ACCIDENT -> "Accident";
            case POTHOLE -> "Pothole";
            case TRAFFIC_JAM -> "Traffic jam";
            case ROAD_HAZARD -> "Road hazard";
            case EMERGENCY -> "Emergency";
            default -> "Hazard";
        };
        String desc = r.getDescription() != null ? r.getDescription().trim().replaceAll("\\s+", " ") : "";
        desc = desc.replaceAll("(?i)\\s+ahead$", "");
        String label = !desc.isEmpty() && desc.length() <= 32 && !desc.startsWith("(") ? VehicleLabels.capitalise(desc) : type;
        boolean sample = r.getReporter() != null && DemoSimulatorService.DEMO_USER_EMAIL.equals(r.getReporter().getEmail());
        return sample ? label + " (sample)" : label;
    }

    static String ago(LocalDateTime when, LocalDateTime now) {
        if (when == null) return "recently";
        long min = Math.max(0, Duration.between(when, now).toMinutes());
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = min / 60;
        if (h < 48) return h == 1 ? "an hour ago" : h + " hours ago";
        long days = h / 24;
        return days + " days ago";
    }

    private static String confirmed(RoadReport r) {
        int yes = r.getConfirmations() != null ? r.getConfirmations() : 0;
        if (yes <= 0) return "";
        return String.format(Locale.ROOT, ", confirmed by %d %s", yes, yes == 1 ? "person" : "people");
    }

    /** For a report that isn't in your path (yet). */
    static String adviceIfPassing(ReportType type) {
        if (type == null) return "Take care if you go that way.";
        return switch (type) {
            case ACCIDENT -> "Keep clear if you go that way.";
            case TRAFFIC_JAM -> "Expect a delay that way.";
            case EMERGENCY -> "Make way if you go that way.";
            default -> "Take care if you go that way.";
        };
    }

    static String advice(ReportType type, TravelMode mode) {
        if (mode.onFoot()) return type == ReportType.ACCIDENT || type == ReportType.EMERGENCY ? "Keep clear." : "Take care.";
        if (type == null) return "Take care.";
        return switch (type) {
            case ACCIDENT -> "Slow down and keep clear.";
            case POTHOLE -> "Slow down.";
            case TRAFFIC_JAM -> "Expect a delay.";
            case EMERGENCY -> "Make way.";
            default -> "Slow down and take care.";
        };
    }
}
