package com.ngumn.backend.service;

import com.ngumn.backend.dto.CommentResponse;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.ReportCommentRepository;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.ViolationComplaintRepository;
import com.ngumn.backend.util.VehicleLabels;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comments on hazard reports and rule-breaker reports - "Still there at
 * 6 pm", "Cleared now" - shown under the report like comments under a post.
 *
 * - Anyone signed in can comment (up to 280 characters; at most 8 comments
 *   in 5 minutes, so nobody can flood a report).
 * - People see commenters by first name only. The driver a rule-breaker
 *   report is about never learns who reported them: the reporter's own
 *   comments show as "Reporter" to that driver.
 * - A comment can be deleted by whoever wrote it, by the person who made
 *   the report (their post, their thread), or by an admin.
 * - The reporter is told about new comments on their report (an alert -
 *   a phone notification when the app is closed), at most once a minute
 *   per report.
 *
 * New and deleted comments are broadcast (COMMENT, plus ROAD_REPORT /
 * COMPLAINT for the count) so open apps refresh.
 */
@Service
public class CommentService {

    static final int MAX_PER_WINDOW = 8;
    static final long WINDOW_MINUTES = 5;
    private static final long NOTIFY_GAP_MS = 60_000;

    private final ReportCommentRepository commentRepository;
    private final RoadReportRepository roadReportRepository;
    private final ViolationComplaintRepository complaintRepository;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;
    private final Map<String, Long> lastNotified = new ConcurrentHashMap<>();

    public CommentService(ReportCommentRepository commentRepository, RoadReportRepository roadReportRepository,
                          ViolationComplaintRepository complaintRepository, AlertService alertService,
                          NgumnWebSocketHandler webSocketHandler) {
        this.commentRepository = commentRepository;
        this.roadReportRepository = roadReportRepository;
        this.complaintRepository = complaintRepository;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
    }

    /** The comments on a report, oldest first (the latest 100). */
    public List<CommentResponse> list(User viewer, VoteTarget type, Long targetId) {
        Target target = find(type, targetId);
        List<ReportComment> latest = new ArrayList<>(commentRepository.findTop100ByTargetTypeAndTargetIdOrderByIdDesc(type, targetId));
        Collections.reverse(latest);
        List<CommentResponse> out = new ArrayList<>();
        for (ReportComment c : latest) out.add(describe(viewer, target, c));
        return out;
    }

    public CommentResponse add(User author, VoteTarget type, Long targetId, String rawText) {
        Target target = find(type, targetId);
        String text = clean(rawText);
        if (text.isEmpty()) throw ApiException.badRequest("Write something first.");
        if (text.length() > ReportComment.MAX_LENGTH) {
            throw ApiException.badRequest("Keep it under " + ReportComment.MAX_LENGTH + " characters.");
        }
        long recent = commentRepository.countByAuthorIdAndCreatedAtAfter(author.getId(),
                LocalDateTime.now().minusMinutes(WINDOW_MINUTES));
        if (recent >= MAX_PER_WINDOW) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've commented a lot in the last few minutes - please wait a little before adding more.");
        }
        ReportComment comment = commentRepository.save(ReportComment.builder()
                .targetType(type)
                .targetId(targetId)
                .author(author)
                .text(text)
                .build());
        saveCount(target);
        notifyReporter(target, author, text);
        return describe(author, target, comment);
    }

    public void delete(User user, Long commentId) {
        ReportComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> ApiException.notFound("That comment was already deleted"));
        Target target = find(comment.getTargetType(), comment.getTargetId());
        if (!canDelete(user, target, comment)) {
            throw ApiException.forbidden("You can only delete your own comments, or comments on your own report.");
        }
        commentRepository.delete(comment);
        saveCount(target);
    }

    // ------------------------------------------------------------------

    /** Trims, and squeezes long runs of blank lines / spaces. */
    static String clean(String raw) {
        if (raw == null) return "";
        return raw.replace("\r", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private CommentResponse describe(User viewer, Target target, ReportComment c) {
        Long authorId = c.getAuthor() != null ? c.getAuthor().getId() : null;
        boolean byReporter = authorId != null && authorId.equals(target.reporterId());
        // The reported driver doesn't get to find out who reported them.
        boolean hideReporter = byReporter && target.isAbout(viewer);
        String name = hideReporter ? "Reporter" : VehicleLabels.firstName(c.getAuthor() != null ? c.getAuthor().getName() : null);
        boolean mine = viewer != null && authorId != null && authorId.equals(viewer.getId());
        return new CommentResponse(c.getId(), c.getTargetType().name(), c.getTargetId(),
                hideReporter ? null : authorId, name != null ? name : "Someone", c.getText(), c.getCreatedAt(),
                mine, byReporter, canDelete(viewer, target, c));
    }

    private static boolean canDelete(User user, Target target, ReportComment c) {
        if (user == null) return false;
        if (user.getRole() == Role.ADMIN) return true;
        if (c.getAuthor() != null && user.getId().equals(c.getAuthor().getId())) return true;
        return user.getId().equals(target.reporterId());
    }

    /**
     * Stores the new comment count - that column only, never the whole
     * report, which could undo a vote or a "not there anymore" made while
     * this comment was being saved.
     */
    private void saveCount(Target target) {
        int count = (int) commentRepository.countByTargetTypeAndTargetId(target.type(), target.id());
        if (target.report() != null) {
            target.report().setCommentCount(count);
            roadReportRepository.updateCommentCount(target.id(), count);
            webSocketHandler.broadcast("ROAD_REPORT", Map.of("id", target.id()));
        } else {
            target.complaint().setCommentCount(count);
            complaintRepository.updateCommentCount(target.id(), count);
            webSocketHandler.broadcast("COMPLAINT", Map.of("id", target.id()));
        }
        webSocketHandler.broadcast("COMMENT", Map.of("targetType", target.type().name(), "targetId", target.id()));
    }

    /**
     * "New comment: Ravi on your pothole report - "Still there at 6 pm"" -
     * to the reporter, unless they wrote it. At most once a minute per report.
     */
    private void notifyReporter(Target target, User author, String text) {
        User reporter = target.reporter();
        if (reporter == null || reporter.getId().equals(author.getId())) return;
        String key = target.type() + ":" + target.id();
        long now = System.currentTimeMillis();
        Long last = lastNotified.get(key);
        if (last != null && now - last < NOTIFY_GAP_MS) return;
        lastNotified.put(key, now);
        if (lastNotified.size() > 5000) lastNotified.values().removeIf(t -> now - t > NOTIFY_GAP_MS);

        String who = VehicleLabels.firstName(author.getName());
        String what = target.report() != null ? "your " + CommunityService.reportWhat(target.report()) : "your rule-breaker report";
        String quote = text.replaceAll("\\s+", " ");
        if (quote.length() > 100) quote = quote.substring(0, 97).trim() + "...";
        String message = "New comment: " + (who != null ? who : "Someone") + " on " + what + " - \"" + quote + "\"";
        alertService.raise(null, reporter, AlertType.ROAD_HAZARD, RiskLevel.LOW, TrafficRuleService.truncate(message, 290),
                target.latitude(), target.longitude(),
                target.report() != null ? target.id() : null, target.complaint() != null ? target.id() : null);
    }

    private Target find(VoteTarget type, Long id) {
        if (type == VoteTarget.REPORT) {
            RoadReport r = roadReportRepository.findById(id).orElseThrow(() -> ApiException.notFound("Report not found"));
            return new Target(type, id, r, null);
        }
        ViolationComplaint c = complaintRepository.findById(id).orElseThrow(() -> ApiException.notFound("Report not found"));
        return new Target(type, id, null, c);
    }

    /** A hazard report or a rule-breaker report - whichever the comment is on. */
    private record Target(VoteTarget type, Long id, RoadReport report, ViolationComplaint complaint) {
        User reporter() {
            return report != null ? report.getReporter() : complaint.getReporter();
        }

        Long reporterId() {
            User r = reporter();
            return r != null ? r.getId() : null;
        }

        Double latitude() {
            return report != null ? report.getLatitude() : complaint.getLatitude();
        }

        Double longitude() {
            return report != null ? report.getLongitude() : complaint.getLongitude();
        }

        /** True if it's a rule-breaker report about `user`'s vehicle. */
        boolean isAbout(User user) {
            if (complaint == null || user == null) return false;
            Vehicle accused = complaint.getAccusedVehicle();
            return accused != null && accused.getOwner() != null && user.getId().equals(accused.getOwner().getId());
        }
    }
}
