package com.ngumn.backend.controller;

import com.ngumn.backend.dto.ReportStatusUpdateRequest;
import com.ngumn.backend.dto.RoadReportRequest;
import com.ngumn.backend.dto.RoadReportResponse;
import com.ngumn.backend.dto.VoteRequest;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CommunityService;
import com.ngumn.backend.service.ReportFeedbackService;
import com.ngumn.backend.service.RoadReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class RoadReportController {

    private final RoadReportService roadReportService;
    private final CommunityService communityService;
    private final ReportFeedbackService feedbackService;
    private final AuthService authService;

    public RoadReportController(RoadReportService roadReportService, CommunityService communityService,
                                ReportFeedbackService feedbackService, AuthService authService) {
        this.roadReportService = roadReportService;
        this.communityService = communityService;
        this.feedbackService = feedbackService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<RoadReportResponse> submit(@RequestHeader("Authorization") String authorization,
                                                       @Valid @RequestBody RoadReportRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(roadReportService.submit(user, request));
    }

    /**
     * Reports still on the road (expired / cleared / rejected ones are left
     * out). With lat / lng, only those within radiusMeters of that point.
     */
    @GetMapping
    public ResponseEntity<List<RoadReportResponse>> recent(@RequestHeader("Authorization") String authorization,
                                                           @RequestParam(required = false) Double lat,
                                                           @RequestParam(required = false) Double lng,
                                                           @RequestParam(required = false) Double radiusMeters) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(roadReportService.recent(user, lat, lng, radiusMeters));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<RoadReportResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(roadReportService.myReports(user));
    }

    /** Community verification: "is this hazard really there?" - true or false. */
    @PostMapping("/{id}/vote")
    public ResponseEntity<RoadReportResponse> vote(@RequestHeader("Authorization") String authorization,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody VoteRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.voteOnReport(user, id, request));
    }

    /** "Helpful" - one tap on someone else's report. */
    @PostMapping("/{id}/helpful")
    public ResponseEntity<RoadReportResponse> markHelpful(@RequestHeader("Authorization") String authorization,
                                                          @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.setReportHelpful(user, id, true));
    }

    /** Takes the "helpful" back. */
    @DeleteMapping("/{id}/helpful")
    public ResponseEntity<RoadReportResponse> unmarkHelpful(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.setReportHelpful(user, id, false));
    }

    /** "Not there anymore" - enough of these take the report down early. */
    @PostMapping("/{id}/gone")
    public ResponseEntity<RoadReportResponse> markGone(@RequestHeader("Authorization") String authorization,
                                                       @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.markGone(user, id));
    }

    /** The reporter takes their own report down ("it's cleared"). */
    @PostMapping("/{id}/clear")
    public ResponseEntity<RoadReportResponse> clear(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(feedbackService.clearOwn(user, id));
    }

    /** Admin moderation override - normally the community verifies reports. */
    @PatchMapping("/{id}/status")
    public ResponseEntity<RoadReportResponse> updateStatus(@RequestHeader("Authorization") String authorization,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody ReportStatusUpdateRequest request) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Only admins can override a report's status");
        }
        return ResponseEntity.ok(roadReportService.updateStatus(user, id, request.getStatus()));
    }
}
