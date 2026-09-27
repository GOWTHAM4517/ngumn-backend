package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.util.GeoUtil;
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
 * Hazard reports reach the people who are actually going to meet them -
 * not everyone who uses the app.
 *
 * - When someone reports a hazard, people near it are told straight away,
 *   but only those heading towards it (or right next to it, or stopped
 *   within 300 m): "Hazard ahead: Pothole, 300 m ahead of you - reported
 *   just now. Slow down."
 * - As people move (every location update), a report on the road in front
 *   of them - within 800 m and within 45 degrees of their direction of
 *   travel - gets the same warning before they reach it.
 *
 * Each person hears about the same report at most once every 20 minutes,
 * never about their own reports, and only while their position is live.
 * Reports that have cleared (expired, taken down or rejected) never warn
 * anyone. The app recognises these alerts by their headline ("Hazard
 * ahead" / "Hazard nearby") and shows them as a spoken heads-up - keep in
 * sync with ngumn-app/src/lib/alerts-store.tsx.
 */
@Service
public class HazardAheadService {

    public static final String HEADLINE_AHEAD = "Hazard ahead";
    public static final String HEADLINE_NEARBY = "Hazard nearby";

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
    static final long LIVE_SECONDS = 180;
    private static final long CACHE_MS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(HazardAheadService.class);

    private final RoadReportRepository roadReportRepository;
    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final Map<String, Long> lastWarned = new ConcurrentHashMap<>();
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

    /** A hazard was just reported: warn the people heading into it. Returns how many were warned. */
    public int warnAboutNewReport(RoadReport report) {
        try {
            refreshSoon(); // the new report must be in the next check's list
            if (report == null || report.getLatitude() == null || report.getLongitude() == null) return 0;
            LocalDateTime now = LocalDateTime.now();
            if (!report.isActiveAt(now)) return 0;
            int warned = 0;
            for (Vehicle v : vehicleRepository.findByStatus(VehicleStatus.ACTIVE)) {
                if (!canReceive(v, now) || isReporter(v, report)) continue;
                if (warnIfRelevant(v, report, now)) warned++;
            }
            return warned;
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
                TrafficRuleService.truncate(message, 290), r.getLatitude(), r.getLongitude());
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
