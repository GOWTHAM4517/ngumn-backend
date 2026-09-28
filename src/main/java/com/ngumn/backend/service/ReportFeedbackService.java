package com.ngumn.backend.service;

import com.ngumn.backend.dto.ComplaintResponse;
import com.ngumn.backend.dto.RoadReportResponse;
import com.ngumn.backend.dto.VoteRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.ReportFeedbackRepository;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.ViolationComplaintRepository;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.ReportLifetime;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * One-tap feedback on reports, and taking reports down once they're over.
 *
 * - 👍 Like / 👎 Dislike (like Facebook): anyone except the reporter (and,
 *   for a rule-breaker report, the driver it's about) can like a report
 *   ("it's true") or dislike it ("it's wrong") - one or the other, tap
 *   again to take it back. Likes make a report trusted and more than two
 *   dislikes take it down (CommunityService.settleReport decides, and pays
 *   out or takes off points). A like also restarts a hazard's clock - it's
 *   still there. A like is what older apps call "Helpful".
 * - Not an issue anymore: once GONE_TO_CLEAR different people say so, a
 *   report (hazard or rule-breaker) is taken off the road early instead of
 *   waiting to expire. Nobody loses points - it was true, it's just over.
 * - Remove: the reporter (or an admin) can take their own report down at
 *   any time - "the jam has cleared".
 * - Older apps' "Still there?" Yes / No: yes is a like, no is "not there
 *   anymore".
 *
 * A report that has been taken down by dislikes can't be liked or disliked
 * any more. Every change is broadcast as ROAD_REPORT / COMPLAINT so open
 * apps refresh, including the reporter's.
 */
@Service
public class ReportFeedbackService {

    /** "Not an issue anymore" taps from different people that clear a report. */
    public static final int GONE_TO_CLEAR = 2;
    /** Older apps' Yes / No answers from further away than this are refused. */
    private static final double MAX_ANSWER_DISTANCE_METERS = 15_000;
    static final String TAKEN_DOWN = "This report was taken down - people said it was wrong.";
    /** Likes keep a hazard up, but at most this many times its normal lifetime from when it was made. */
    static final int MAX_LIFETIMES = 3;

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
    // Like / Dislike
    // ------------------------------------------------------------------

    /** "LIKE" -> HELPFUL, "DISLIKE" -> DISLIKE, "NONE" / empty -> null (neither). */
    static FeedbackKind reactionKind(String reaction) {
        if (reaction == null || reaction.isBlank()) return null;
        return switch (reaction.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LIKE", "HELPFUL" -> FeedbackKind.HELPFUL;
            case "DISLIKE" -> FeedbackKind.DISLIKE;
            case "NONE" -> null;
            default -> throw ApiException.badRequest("Reaction must be LIKE, DISLIKE or NONE");
        };
    }

    /** Likes, dislikes or (NONE) takes back your reaction to a hazard report. Safe to repeat. */
    public synchronized RoadReportResponse reactToReport(User user, Long reportId, String reaction) {
        RoadReport report = findReport(reportId);
        if (isReporter(user, report.getReporter())) {
            throw ApiException.badRequest("That's your own report - others can like or dislike it.");
        }
        if (report.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        FeedbackKind kind = reactionKind(reaction);
        boolean newLike = kind == FeedbackKind.HELPFUL && !has(user, VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL);
        setFeedback(user, VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL, kind == FeedbackKind.HELPFUL);
        setFeedback(user, VoteTarget.REPORT, report.getId(), FeedbackKind.DISLIKE, kind == FeedbackKind.DISLIKE);
        if (newLike) keepUp(report);
        return saveReportCounts(user, report);
    }

    /** Likes, dislikes or takes back your reaction to a rule-breaker report. The reported driver can't. */
    public synchronized ComplaintResponse reactToComplaint(User user, Long complaintId, String reaction) {
        ViolationComplaint complaint = findReactableComplaint(user, complaintId);
        if (complaint.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        FeedbackKind kind = reactionKind(reaction);
        setFeedback(user, VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.HELPFUL, kind == FeedbackKind.HELPFUL);
        setFeedback(user, VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.DISLIKE, kind == FeedbackKind.DISLIKE);
        return saveComplaintCounts(user, complaint);
    }

    /**
     * Older apps' "Helpful" heart: marking helpful is a like (and drops a
     * dislike); un-marking only takes the like back.
     */
    public synchronized RoadReportResponse setReportHelpful(User user, Long reportId, boolean helpful) {
        if (helpful) return reactToReport(user, reportId, "LIKE");
        RoadReport report = findReport(reportId);
        if (isReporter(user, report.getReporter())) {
            throw ApiException.badRequest("That's your own report - others can like or dislike it.");
        }
        if (report.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        setFeedback(user, VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL, false);
        return saveReportCounts(user, report);
    }

    /** Older apps' "Helpful" on a rule-breaker report - see setReportHelpful. */
    public synchronized ComplaintResponse setComplaintHelpful(User user, Long complaintId, boolean helpful) {
        if (helpful) return reactToComplaint(user, complaintId, "LIKE");
        ViolationComplaint complaint = findReactableComplaint(user, complaintId);
        if (complaint.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        setFeedback(user, VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.HELPFUL, false);
        return saveComplaintCounts(user, complaint);
    }

    private ViolationComplaint findReactableComplaint(User user, Long complaintId) {
        ViolationComplaint complaint = findComplaint(complaintId);
        if (isReporter(user, complaint.getReporter())) {
            throw ApiException.badRequest("That's your own report - others can like or dislike it.");
        }
        if (isAccused(user, complaint)) {
            throw ApiException.badRequest("This report is about your vehicle.");
        }
        return complaint;
    }

    /**
     * A new like says it's still there: restart its clock, so a hazard
     * people keep seeing stays up - but never past MAX_LIFETIMES times its
     * normal lifetime from when it was made, so liking it again and again
     * can't keep it up forever.
     */
    private static void keepUp(RoadReport report) {
        LocalDateTime now = LocalDateTime.now();
        if (!report.isActiveAt(now)) return;
        java.time.Duration life = ReportLifetime.of(report.getType(), report.getDescription());
        LocalDateTime made = report.getTimestamp() != null ? report.getTimestamp() : now;
        LocalDateTime renewed = now.plus(life);
        LocalDateTime latest = made.plus(life.multipliedBy(MAX_LIFETIMES));
        if (renewed.isAfter(latest)) renewed = latest;
        if (renewed.isAfter(report.effectiveExpiresAt())) report.setExpiresAt(renewed);
    }

    private boolean has(User user, VoteTarget target, Long targetId, FeedbackKind kind) {
        return feedbackRepository.findByTargetTypeAndTargetIdAndUserIdAndKind(target, targetId, user.getId(), kind).isPresent();
    }

    /** Stores the new counts, then lets the likes and dislikes decide the report (CommunityService.settleReport). */
    private RoadReportResponse saveReportCounts(User viewer, RoadReport report) {
        int likes = count(VoteTarget.REPORT, report.getId(), FeedbackKind.HELPFUL);
        int dislikes = count(VoteTarget.REPORT, report.getId(), FeedbackKind.DISLIKE);
        report.setHelpfulCount(likes);
        report.setDislikeCount(dislikes);
        // Older apps show likes as "confirmations" and dislikes as "denials".
        report.setConfirmations(likes);
        report.setDenials(dislikes);
        report = roadReportRepository.save(report);
        report = communityService.settleReport(report);
        webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        return communityService.describeReport(viewer, report);
    }

    private ComplaintResponse saveComplaintCounts(User viewer, ViolationComplaint complaint) {
        int likes = count(VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.HELPFUL);
        int dislikes = count(VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.DISLIKE);
        complaint.setHelpfulCount(likes);
        complaint.setDislikeCount(dislikes);
        complaint.setConfirmations(likes);
        complaint.setDenials(dislikes);
        complaint = complaintRepository.save(complaint);
        complaint = communityService.settleComplaint(complaint);
        webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        return communityService.describeComplaint(viewer, complaint);
    }

    // ------------------------------------------------------------------
    // Older apps' "Still there?" Yes / No
    // ------------------------------------------------------------------

    /** Yes is a like; No is "not there anymore". Only people nearby can answer. */
    public synchronized RoadReportResponse answerReport(User user, Long reportId, VoteRequest request) {
        RoadReport report = findReport(reportId);
        if (isReporter(user, report.getReporter())) throw ApiException.badRequest("You can't vote on your own report");
        checkNear(request, report.getLatitude(), report.getLongitude());
        if (report.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        if (!report.isActiveAt(LocalDateTime.now())) {
            throw ApiException.badRequest("This report has already cleared - thanks for checking!");
        }
        return request != null && Boolean.TRUE.equals(request.getAgree())
                ? reactToReport(user, reportId, "LIKE")
                : markGone(user, reportId);
    }

    /** Yes is a like; No is "not an issue anymore". Not the reporter or the driver it's about. */
    public synchronized ComplaintResponse answerComplaint(User user, Long complaintId, VoteRequest request) {
        ViolationComplaint complaint = findReactableComplaint(user, complaintId);
        checkNear(request, complaint.getLatitude(), complaint.getLongitude());
        if (complaint.getStatus() == ReportStatus.REJECTED) throw ApiException.badRequest(TAKEN_DOWN);
        if (!complaint.isActiveAt(LocalDateTime.now())) {
            throw ApiException.badRequest("This report has already cleared - thanks for checking!");
        }
        return request != null && Boolean.TRUE.equals(request.getAgree())
                ? reactToComplaint(user, complaintId, "LIKE")
                : markComplaintGone(user, complaintId);
    }

    private static void checkNear(VoteRequest request, Double lat, Double lng) {
        if (request == null || request.getLatitude() == null || request.getLongitude() == null || lat == null || lng == null) return;
        double d = GeoUtil.distanceMeters(request.getLatitude(), request.getLongitude(), lat, lng);
        if (d > MAX_ANSWER_DISTANCE_METERS) {
            throw ApiException.badRequest(String.format(Locale.ROOT,
                    "You're about %.0f km away - only people nearby can confirm this.", d / 1000));
        }
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
            throw ApiException.forbidden("Only the person who reported this can remove it.");
        }
        if (report.getClearedAt() == null) {
            report.setClearedAt(LocalDateTime.now());
            report.setClearedBy(isReporter(user, report.getReporter()) ? "REPORTER" : "ADMIN");
            report = roadReportRepository.save(report);
            webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", report.getId()));
        }
        return communityService.describeReport(user, report);
    }

    /**
     * "Not an issue anymore" on a rule-breaker report, from someone other
     * than the reporter (theirs is just removed) or the driver it's about.
     * Clears it after GONE_TO_CLEAR people.
     */
    public synchronized ComplaintResponse markComplaintGone(User user, Long complaintId) {
        ViolationComplaint complaint = findComplaint(complaintId);
        if (isReporter(user, complaint.getReporter())) {
            return clearOwnComplaint(user, complaintId);
        }
        if (isAccused(user, complaint)) {
            throw ApiException.badRequest("This report is about your vehicle.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!complaint.isActiveAt(now)) {
            return communityService.describeComplaint(user, complaint);
        }
        setFeedback(user, VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.GONE, true);
        int gone = count(VoteTarget.COMPLAINT, complaint.getId(), FeedbackKind.GONE);
        complaint.setGoneCount(gone);
        if (gone >= GONE_TO_CLEAR) {
            complaint.setClearedAt(now);
            complaint.setClearedBy("COMMUNITY");
        }
        complaint = complaintRepository.save(complaint);
        webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        return communityService.describeComplaint(user, complaint);
    }

    /** The reporter (or an admin) takes a rule-breaker report down now. Safe to repeat. */
    public synchronized ComplaintResponse clearOwnComplaint(User user, Long complaintId) {
        ViolationComplaint complaint = findComplaint(complaintId);
        boolean admin = user.getRole() == Role.ADMIN;
        if (!isReporter(user, complaint.getReporter()) && !admin) {
            throw ApiException.forbidden("Only the person who reported this can remove it.");
        }
        if (complaint.getClearedAt() == null) {
            complaint.setClearedAt(LocalDateTime.now());
            complaint.setClearedBy(isReporter(user, complaint.getReporter()) ? "REPORTER" : "ADMIN");
            complaint = complaintRepository.save(complaint);
            webSocketHandler.broadcast("COMPLAINT", Map.of("id", complaint.getId()));
        }
        return communityService.describeComplaint(user, complaint);
    }

    // ------------------------------------------------------------------
    // Demo Mode
    // ------------------------------------------------------------------

    /**
     * Demo Mode only: one simulated person likes one open report or
     * rule-breaker report, so trust can be shown with a single phone.
     * Simulated people never dislike - they must not take anyone's real
     * report down or cost them points. Called by DemoSimulatorService while
     * the demo runs.
     */
    public void demoReact(List<User> demoVoters, Random random) {
        if (demoVoters.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(6);
        List<RoadReport> reports = roadReportRepository.findByStatusAndTimestampAfter(ReportStatus.PENDING, since)
                .stream().filter(r -> r.isActiveAt(now)).toList();
        List<ViolationComplaint> complaints = complaintRepository.findByStatusAndCreatedAtAfter(ReportStatus.PENDING, since)
                .stream().filter(c -> c.isActiveAt(now)).toList();
        int total = reports.size() + complaints.size();
        if (total == 0) return;

        int pick = random.nextInt(total);
        User person = demoVoters.get(random.nextInt(demoVoters.size()));
        String reaction = "LIKE";
        try {
            if (pick < reports.size()) {
                Long id = reports.get(pick).getId();
                if (!reacted(person, VoteTarget.REPORT, id)) reactToReport(person, id, reaction);
            } else {
                Long id = complaints.get(pick - reports.size()).getId();
                if (!reacted(person, VoteTarget.COMPLAINT, id)) reactToComplaint(person, id, reaction);
            }
        } catch (ApiException ignored) {
            // This demo person can't react to it (e.g. it's theirs) - another one will next tick.
        }
    }

    private boolean reacted(User user, VoteTarget target, Long targetId) {
        return feedbackRepository.findByTargetTypeAndTargetIdAndUserIdAndKind(target, targetId, user.getId(), FeedbackKind.HELPFUL).isPresent()
                || feedbackRepository.findByTargetTypeAndTargetIdAndUserIdAndKind(target, targetId, user.getId(), FeedbackKind.DISLIKE).isPresent();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private RoadReport findReport(Long reportId) {
        return roadReportRepository.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
    }

    private ViolationComplaint findComplaint(Long complaintId) {
        return complaintRepository.findById(complaintId)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
    }

    private static boolean isReporter(User user, User reporter) {
        return reporter != null && user != null && reporter.getId().equals(user.getId());
    }

    /** The driver a rule-breaker report is about. */
    private static boolean isAccused(User user, ViolationComplaint complaint) {
        Vehicle accused = complaint.getAccusedVehicle();
        return user != null && accused != null && accused.getOwner() != null && accused.getOwner().getId().equals(user.getId());
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
