package com.ngumn.backend.controller;

import com.ngumn.backend.dto.CommentRequest;
import com.ngumn.backend.dto.CommentResponse;
import com.ngumn.backend.dto.ComplaintRequest;
import com.ngumn.backend.dto.ComplaintResponse;
import com.ngumn.backend.dto.ReactionRequest;
import com.ngumn.backend.dto.VoteRequest;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.VoteTarget;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CommentService;
import com.ngumn.backend.service.CommunityService;
import com.ngumn.backend.service.ReportFeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "Report a rule-breaker" - complaints about other drivers, trusted or
 * taken down by people nearby liking or disliking them rather than by an
 * admin (see CommunityService).
 */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

    private final CommunityService communityService;
    private final ReportFeedbackService feedbackService;
    private final CommentService commentService;
    private final AuthService authService;

    public ComplaintController(CommunityService communityService, ReportFeedbackService feedbackService,
                               CommentService commentService, AuthService authService) {
        this.communityService = communityService;
        this.feedbackService = feedbackService;
        this.commentService = commentService;
        this.authService = authService;
    }

    /** One rule-breaker report by id - e.g. opened from a notification. */
    @GetMapping("/{id}")
    public ResponseEntity<ComplaintResponse> one(@RequestHeader("Authorization") String authorization,
                                                 @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.complaint(user, id));
    }

    /** Like / dislike a rule-breaker report, or take it back: {"reaction": "LIKE" | "DISLIKE" | "NONE"}. */
    @PostMapping("/{id}/reaction")
    public ResponseEntity<ComplaintResponse> react(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id,
                                                   @RequestBody ReactionRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.reactToComplaint(user, id, request.getReaction()));
    }

    /** Comments on a rule-breaker report, oldest first. */
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<CommentResponse>> comments(@RequestHeader("Authorization") String authorization,
                                                          @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(commentService.list(user, VoteTarget.COMPLAINT, id));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentResponse> comment(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody CommentRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(commentService.add(user, VoteTarget.COMPLAINT, id, request.getText()));
    }

    @PostMapping
    public ResponseEntity<ComplaintResponse> file(@RequestHeader("Authorization") String authorization,
                                                  @Valid @RequestBody ComplaintRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.fileComplaint(user, request));
    }

    /** The latest rule-breaker reports - with lat / lng, only those within radiusMeters of that point. */
    @GetMapping
    public ResponseEntity<List<ComplaintResponse>> recent(@RequestHeader("Authorization") String authorization,
                                                          @RequestParam(required = false) Double lat,
                                                          @RequestParam(required = false) Double lng,
                                                          @RequestParam(required = false) Double radiusMeters) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.recentComplaints(user, lat, lng, radiusMeters));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ComplaintResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.myComplaints(user));
    }

    /** Older apps' "Did you see it?" Yes / No - yes is a like, no is "not an issue anymore". */
    @PostMapping("/{id}/vote")
    public ResponseEntity<ComplaintResponse> vote(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody VoteRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.answerComplaint(user, id, request));
    }

    /** "Not an issue anymore" - two of these take the report down early. */
    @PostMapping("/{id}/gone")
    public ResponseEntity<ComplaintResponse> markGone(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.markComplaintGone(user, id));
    }

    /** Remove: the reporter takes their own rule-breaker report down. */
    @PostMapping("/{id}/clear")
    public ResponseEntity<ComplaintResponse> clear(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.clearOwnComplaint(user, id));
    }

    /** "Helpful" - one tap on someone else's rule-breaker report. */
    @PostMapping("/{id}/helpful")
    public ResponseEntity<ComplaintResponse> markHelpful(@RequestHeader("Authorization") String authorization,
                                                         @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.setComplaintHelpful(user, id, true));
    }

    @DeleteMapping("/{id}/helpful")
    public ResponseEntity<ComplaintResponse> unmarkHelpful(@RequestHeader("Authorization") String authorization,
                                                           @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.setComplaintHelpful(user, id, false));
    }
}
