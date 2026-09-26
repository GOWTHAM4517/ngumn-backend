package com.ngumn.backend.controller;

import com.ngumn.backend.dto.RewardResponse;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.RewardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rewards")
public class RewardController {

    private final RewardService rewardService;
    private final AuthService authService;

    public RewardController(RewardService rewardService, AuthService authService) {
        this.rewardService = rewardService;
        this.authService = authService;
    }

    @GetMapping("/mine")
    public ResponseEntity<List<RewardResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(rewardService.forUser(user.getId()).stream()
                .map(RewardResponse::from).collect(Collectors.toList()));
    }
}
