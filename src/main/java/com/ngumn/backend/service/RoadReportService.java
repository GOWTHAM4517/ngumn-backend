package com.ngumn.backend.service;

import com.ngumn.backend.dto.RoadReportRequest;
import com.ngumn.backend.dto.RoadReportResponse;
import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.ReportType;
import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.ReportKind;
import com.ngumn.backend.util.ReportLifetime;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Hazard reports. Reports are verified by the community (people nearby
 * answer "is this true?" - see CommunityService); the admin status change
 * below is kept as a moderation override.
 *
 * Reports don't stay up forever: something that passes clears by itself
 * (ReportKind - an hour for a traffic jam, half an hour for an animal on
 * the road), a pothole stays until it's fixed, and any report can be taken
 * down early (ReportFeedbackService). The public list only returns reports
 * that are still on the road.
 *
 * One spot, one report: when someone reports what's already reported
 * there (the same kind of thing within ReportKind.sameSpotMeters - 50 m
 * for a pothole, 400 m for a traffic jam), their report is added to the
 * one already there instead of making a second one. It counts as "it's
 * still there" and as one more person who reported it.
 */
@Service
public class RoadReportService {

    private static final Logger log = LoggerFactory.getLogger(RoadReportService.class);
    private static final int VERIFIED_REPORT_POINTS = CommunityService.REPORTER_POINTS;

    private final RoadReportRepository roadReportRepository;
    private final RewardService rewardService;
    private final CommunityService communityService;
    private final NgumnWebSocketHandler webSocketHandler;
    private final HazardAheadService hazardAheadService;
    private final ReportFeedbackService reportFeedbackService;

    public RoadReportService(RoadReportRepository roadReportRepository, RewardService rewardService,
                              CommunityService communityService, NgumnWebSocketHandler webSocketHandler,
                              HazardAheadService hazardAheadService, ReportFeedbackService reportFeedbackService) {
        this.roadReportRepository = roadReportRepository;
        this.rewardService = rewardService;
        this.communityService = communityService;
        this.webSocketHandler = webSocketHandler;
        this.hazardAheadService = hazardAheadService;
        this.reportFeedbackService = reportFeedbackService;
    }

    public synchronized RoadReportResponse submit(User reporter, RoadReportRequest request) {
        // Already reported here? Add this one to it - one spot, one report.
        RoadReport same = sameSpot(reporter, request.getType(), request.getDescription(), request.getLatitude(), request.getLongitude());
        RoadReport joined = same != null ? reportFeedbackService.addReporter(reporter, same.getId(), request.getImageUrl()) : null;
        if (joined != null) {
            RoadReportResponse out = communityService.describeReport(reporter, joined);
            out.setMerged(true);
            return out;
        }
        RoadReport report = RoadReport.builder()
                .reporter(reporter)
                .type(request.getType())
                .description(request.getDescription())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .imageUrl(request.getImageUrl())
                .status(ReportStatus.PENDING)
                .confirmations(0)
                .denials(0)
                .build();
        report = roadReportRepository.save(report);
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        // Only the people heading into it are warned - not everyone.
        hazardAheadService.warnAboutNewReport(report);
        return communityService.describeReport(reporter, report);
    }

    /**
     * The report already on the road for the same thing at this spot (within
     * that kind's same-spot distance), the nearest one - or null. Demo Mode's
     * sample reports and real ones are never mixed.
     */
    RoadReport sameSpot(User reporter, ReportType type, String description, Double lat, Double lng) {
        if (type == null || lat == null || lng == null) return null;
        ReportKind kind = ReportKind.of(type, description);
        boolean sample = isSample(reporter);
        RoadReport nearest = null;
        double nearestD = Double.MAX_VALUE;
        for (RoadReport r : activeReports(LocalDateTime.now(), Integer.MAX_VALUE)) {
            if (r.getLatitude() == null || r.getLongitude() == null) continue;
            if (isSample(r.getReporter()) != sample) continue;
            if (!ReportKind.sameThing(type, description, r.getType(), r.getDescription())) continue;
            double d = GeoUtil.distanceMeters(lat, lng, r.getLatitude(), r.getLongitude());
            if (d <= kind.sameSpotMeters && d < nearestD) {
                nearest = r;
                nearestD = d;
            }
        }
        return nearest;
    }

    /** Demo Mode's sample person. */
    private static boolean isSample(User u) {
        return u != null && DemoSimulatorService.DEMO_USER_EMAIL.equals(u.getEmail());
    }

    /** Reports still on the road right now, newest first - expired, cleared and rejected ones drop off. */
    public List<RoadReportResponse> recent(User viewer) {
        return recent(viewer, null, null, null);
    }

    /**
     * Reports still on the road, newest first. With lat / lng, only those
     * within radiusMeters (default 10 km, at most 20 km) of that point - the
     * app asks only for the area around the person, so a pothole in
     * another city never reaches them.
     */
    public List<RoadReportResponse> recent(User viewer, Double lat, Double lng, Double radiusMeters) {
        List<RoadReport> active = activeReports(LocalDateTime.now(), 500);
        if (lat != null && lng != null) {
            double radius = VehicleService.clampRadius(radiusMeters, 10_000);
            active = active.stream()
                    .filter(r -> r.getLatitude() != null && r.getLongitude() != null
                            && GeoUtil.distanceMeters(lat, lng, r.getLatitude(), r.getLongitude()) <= radius)
                    .toList();
        }
        return communityService.describeReports(viewer, active.stream().limit(200).toList());
    }

    /** Active reports (see RoadReport.isActiveAt), newest first, at most `limit`. */
    public List<RoadReport> activeReports(LocalDateTime now, int limit) {
        return roadReportRepository
                .findPossiblyActive(ReportStatus.REJECTED, now, now.minus(ReportLifetime.MAX))
                .stream()
                .filter(r -> r.isActiveAt(now))
                .limit(limit)
                .toList();
    }

    /**
     * Reports made before expiry times were stored get one now, worked out
     * from when they were made - so every query can rely on expiresAt.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExpiry() {
        try {
            List<RoadReport> missing = roadReportRepository.findByExpiresAtIsNull();
            if (!missing.isEmpty()) {
                for (RoadReport r : missing) r.setExpiresAt(r.effectiveExpiresAt());
                roadReportRepository.saveAll(missing);
                log.info("Set expiry times on {} older road reports", missing.size());
            }
        } catch (RuntimeException e) {
            // Not fatal: RoadReport.effectiveExpiresAt covers rows without one.
            log.warn("Couldn't backfill report expiry times: {}", e.getMessage());
        }
        extendLastingReports();
    }

    /**
     * Potholes and road work reported before they lasted until fixed were
     * given a week (or 3 days): give the ones still up their full lifetime
     * now, so they don't vanish while the pothole is still there.
     */
    void extendLastingReports() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<RoadReport> changed = new java.util.ArrayList<>();
            for (RoadReport r : activeReports(now, Integer.MAX_VALUE)) {
                if (!ReportLifetime.lasting(r.getType(), r.getDescription()) || r.getTimestamp() == null) continue;
                LocalDateTime full = r.getTimestamp().plus(ReportLifetime.of(r.getType(), r.getDescription()));
                if (full.isAfter(r.effectiveExpiresAt())) {
                    r.setExpiresAt(full);
                    changed.add(r);
                }
            }
            if (changed.isEmpty()) return;
            roadReportRepository.saveAll(changed);
            log.info("Potholes and road work now last until fixed: extended {} reports", changed.size());
        } catch (RuntimeException e) {
            log.warn("Couldn't extend pothole reports: {}", e.getMessage());
        }
    }

    public List<RoadReportResponse> myReports(User viewer) {
        return communityService.describeReports(viewer,
                roadReportRepository.findByReporterIdOrderByTimestampDesc(viewer.getId()));
    }

    /** Admin moderation override (the community normally decides - see CommunityService). */
    public RoadReportResponse updateStatus(User admin, Long reportId, ReportStatus newStatus) {
        RoadReport report = roadReportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        ReportStatus previous = report.getStatus();
        report.setStatus(newStatus);
        if (newStatus != ReportStatus.PENDING && previous != newStatus) {
            report.setDecidedAt(LocalDateTime.now());
        }
        report = roadReportRepository.save(report);

        if (newStatus == ReportStatus.VERIFIED && previous != ReportStatus.VERIFIED) {
            rewardService.grant(report.getReporter(), VERIFIED_REPORT_POINTS,
                    "Your " + CommunityService.reportWhat(report) + " was verified", report);
        }

        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(admin, report);
    }
}
