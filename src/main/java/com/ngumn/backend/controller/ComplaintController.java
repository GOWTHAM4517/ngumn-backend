package com.ngumn.backend.controller;

import com.ngumn.backend.dto.ComplaintRequest;
import com.ngumn.backend.dto.ComplaintResponse;
import com.ngumn.backend.dto.VoteRequest;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CommunityService;
import com.ngumn.backend.service.ReportFeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * "Report a rule-breaker" - complaints about other drivers, verified by
 * people nearby rather than an admin (see CommunityService).
 */
@RestController
@RequestMapping("/api/complaints")
public class ComplaintController {

    private final CommunityService communityService;
    private final ReportFeedbackService feedbackService;
    private final AuthService authService;

    public ComplaintController(CommunityService communityService, ReportFeedbackService feedbackService,
                               AuthService authService) {
        this.communityService = communityService;
        this.feedbackService = feedbackService;
        this.authService = authService;
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

    @PostMapping("/{id}/vote")
    public ResponseEntity<ComplaintResponse> vote(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody VoteRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.voteOnComplaint(user, id, request));
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
