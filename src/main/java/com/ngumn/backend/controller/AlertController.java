package com.ngumn.backend.controller;

import com.ngumn.backend.dto.AlertResponse;
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

    @GetMapping
    public ResponseEntity<List<AlertResponse>> recent(@RequestHeader("Authorization") String authorization) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.recent());
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<AlertResponse>> forVehicle(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable Long vehicleId) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.forVehicle(vehicleId));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<AlertResponse> acknowledge(@RequestHeader("Authorization") String authorization,
                                                       @PathVariable Long id) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(alertService.acknowledge(id));
    }
}
