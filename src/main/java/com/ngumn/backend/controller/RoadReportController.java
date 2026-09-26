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
    private final AuthService authService;

    public RoadReportController(RoadReportService roadReportService, CommunityService communityService,
                                AuthService authService) {
        this.roadReportService = roadReportService;
        this.communityService = communityService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<RoadReportResponse> submit(@RequestHeader("Authorization") String authorization,
                                                       @Valid @RequestBody RoadReportRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(roadReportService.submit(user, request));
    }

    @GetMapping
    public ResponseEntity<List<RoadReportResponse>> recent(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(roadReportService.recent(user));
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
