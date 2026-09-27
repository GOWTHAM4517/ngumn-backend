package com.ngumn.backend.service;

import com.ngumn.backend.dto.ComplaintResponse;
import com.ngumn.backend.dto.RoadReportResponse;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.ReportFeedbackRepository;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.ViolationComplaintRepository;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One-tap feedback on reports, and taking reports down once they're over.
 *
 * - Helpful (the heart): anyone except the reporter can mark a hazard or
 *   rule-breaker report helpful, and tap again to take it back. The count
 *   is shown on the report and the reporter is told about it in the app.
 * - Not there anymore: once GONE_TO_CLEAR different people say so, a
 *   hazard is taken off the road early instead of waiting to expire.
 * - Clear: the reporter (or an admin) can take their own report down at
 *   any time - "the jam has cleared".
 *
 * Every change is broadcast as ROAD_REPORT / COMPLAINT so open apps
 * refresh, including the reporter's.
 */
@Service
public class ReportFeedbackService {

    /** "Not there anymore" taps from different people that clear a report. */
    public static final int GONE_TO_CLEAR = 2;

    private final RoadReportRepository roadReportRepository;
    private final ViolationComplaintRepository complaintRepository;
    private final ReportFeedbackRepository feedbackRepository;
    private final CommunityService communityService;
    private final NgumnWebSocketHandler webSocketHandler;

    public ReportFeedbackService(RoadReportRepository roadReportRepository,
                                 ViolationComplaintRepository complaintRepository,
                                 ReportFeedbackRepository feedbackRepository,
                                 CommunityService communityService,
                                 NgumnWebSocketHandler webSocketHandler) {
        this.roadReportRepository = roadReportRepository;
        this.complaintRepository = complaintRepository;
        this.feedbackRepository = feedbackRepository;
        this.communityService = communityService;
        this.webSocketHandler = webSocketHandler;
    }

    // ------------------------------------------------------------------
    // Helpful
    // ------------------------------------------------------------------

    /** Marks (helpful = true) or un-marks a hazard report as helpful. Safe to repeat. */
    public synchronized RoadReportResponse setReportHelpful(User user, Long reportId, boolean helpful) {
        RoadReport report = findReport(reportId);
        if (isReporter(user, report.getReporter())) {
            throw ApiException.badRequest("That's your own report - others can mark it helpful.");
        }
        setFeedback(user, VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL, helpful);
        report.setHelpfulCount(count(VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL));
        report = roadReportRepository.save(report);
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(user, report);
    }

    /** Marks or un-marks a rule-breaker report as helpful. The reported driver can't. */
    public synchronized ComplaintResponse setComplaintHelpful(User user, Long complaintId, boolean helpful) {
        ViolationComplaint complaint = complaintRepository.findById(complaintId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        if (isReporter(user, complaint.getReporter())) {
            throw ApiException.badRequest("That's your own report - others can mark it helpful.");
        }
        Vehicle accused = complaint.getAccusedVehicle();
        if (accused != null && accused.getOwner() != null && accused.getOwner().getId().equals(user.getId())) {
            throw ApiException.badRequest("This report is about your vehicle.");
        }
        setFeedback(user, VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.HELPFUL, helpful);
        complaint.setHelpfulCount(count(VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.HELPFUL));
        complaint = complaintRepository.save(complaint);
        webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        return communityService.describeComplaint(user, complaint);
    }

    // ------------------------------------------------------------------
    // Taking reports down
    // ------------------------------------------------------------------

    /** "Not there anymore" from someone other than the reporter. Clears the report after GONE_TO_CLEAR people. */
    public synchronized RoadReportResponse markGone(User user, Long reportId) {
        RoadReport report = findReport(reportId);
        if (isReporter(user, report.getReporter())) {
            // Their own report: just take it down.
            return clearOwn(user, reportId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!report.isActiveAt(now)) {
            return communityService.describeReport(user, report);
        }
        setFeedback(user, VoteTarget.REPORT, report.getId(), FeedbackKind.GONE, true);
        int gone = count(VoteTarget.REPORT, report.getId(), FeedbackKind.GONE);
        report.setGoneCount(gone);
        if (gone >= GONE_TO_CLEAR) {
            report.setClearedAt(now);
            report.setClearedBy("COMMUNITY");
        }
        report = roadReportRepository.save(report);
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(user, report);
    }

    /** The reporter (or an admin) takes a report down now. Safe to repeat. */
    public synchronized RoadReportResponse clearOwn(User user, Long reportId) {
        RoadReport report = findReport(reportId);
        boolean admin = user.getRole() == Role.ADMIN;
        if (!isReporter(user, report.getReporter()) && !admin) {
            throw ApiException.forbidden("Only the person who reported this can clear it.");
        }
        if (report.getClearedAt() == null) {
            report.setClearedAt(LocalDateTime.now());
            report.setClearedBy(isReporter(user, report.getReporter()) ? "REPORTER" : "ADMIN");
            report = roadReportRepository.save(report);
            webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        }
        return communityService.describeReport(user, report);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private RoadReport findReport(Long reportId) {
        return roadReportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
    }

    private static boolean isReporter(User user, User reporter) {
        return reporter != null && user != null && reporter.getId().equals(user.getId());
    }

    private void setFeedback(User user, VoteTarget target, Long targetId, FeedbackKind kind, boolean on) {
        var existing = feedbackRepository.findByTargetTypeAndTargetIdAndUserIdAndKind(target, targetId, user.getId(), kind);
        if (on && existing.isEmpty()) {
            try {
                feedbackRepository.save(ReportFeedback.builder()
                        .targetType(target)
                        .targetId(targetId)
                        .user(user)
                        .kind(kind)
                        .build());
            } catch (DataIntegrityViolationException alreadyThere) {
                // A double tap from two devices got there first - same result.
            }
        } else if (!on) {
            existing.ifPresent(feedbackRepository::delete);
        }
    }

    private int count(VoteTarget target, Long targetId, FeedbackKind kind) {
        return (int) feedbackRepository.countByTargetTypeAndTargetIdAndKind(target, targetId, kind);
    }
}
