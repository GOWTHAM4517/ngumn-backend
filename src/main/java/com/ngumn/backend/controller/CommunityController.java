package com.ngumn.backend.controller;

import com.ngumn.backend.dto.TrustResponse;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CommunityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Trust scores from community verification (see CommunityService). */
@RestController
@RequestMapping("/api/community")
public class CommunityController {

    private final CommunityService communityService;
    private final AuthService authService;

    public CommunityController(CommunityService communityService, AuthService authService) {
        this.communityService = communityService;
        this.authService = authService;
    }

    @GetMapping("/trust/me")
    public ResponseEntity<TrustResponse> myTrust(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.trustFor(user.getId()));
    }

    @GetMapping("/trust/{userId}")
    public ResponseEntity<TrustResponse> trust(@RequestHeader("Authorization") String authorization,
                                               @PathVariable Long userId) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(communityService.trustFor(userId));
    }
}
