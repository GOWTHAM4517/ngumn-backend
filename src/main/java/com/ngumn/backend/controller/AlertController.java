package com.ngumn.backend.controller;

import com.ngumn.backend.dto.AlertResponse;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AlertService;
import com.ngumn.backend.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AuthService authService;

    public AlertController(AlertService alertService, AuthService authService) {
        this.alertService = alertService;
        this.authService = authService;
    }

    /** Latest alerts - with lat / lng, only those raised within radiusMeters of that point. */
    @GetMapping
    public ResponseEntity<List<AlertResponse>> recent(@RequestHeader("Authorization") String authorization,
                                                      @RequestParam(required = false) Double lat,
                                                      @RequestParam(required = false) Double lng,
                                                      @RequestParam(required = false) Double radiusMeters) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.recent(lat, lng, radiusMeters));
    }

    /** Your own alerts (from any of your vehicles), newest first. */
    @GetMapping("/mine")
    public ResponseEntity<List<AlertResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.forUser(user));
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<AlertResponse>> forVehicle(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable Long vehicleId) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.forVehicle(user, vehicleId));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<AlertResponse> acknowledge(@RequestHeader("Authorization") String authorization,
                                                       @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.acknowledge(user, id));
    }
}
