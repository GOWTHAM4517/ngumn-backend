package com.ngumn.backend.controller;

import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.DemoSimulatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Starts/stops the simulated-traffic demo (used for showing the app
 * working without real hardware or several physical devices). Admin-only:
 * once the backend is reachable from the public internet, an
 * unauthenticated visitor must not be able to flood the database with
 * simulated vehicles/reports or spawn fake emergencies.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoSimulatorService demoSimulatorService;
    private final AuthService authService;

    public DemoController(DemoSimulatorService demoSimulatorService, AuthService authService) {
        this.demoSimulatorService = demoSimulatorService;
        this.authService = authService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(@RequestHeader("Authorization") String authorization,
                                                       @RequestParam(defaultValue = "6") int vehicleCount,
                                                       @RequestParam(required = false) Double lat,
                                                       @RequestParam(required = false) Double lng) {
        requireAdmin(authorization);
        // lat / lng: start the sample traffic around that point (e.g. where
        // the demo is being shown) instead of the default area.
        String message = demoSimulatorService.start(vehicleCount, lat, lng);
        return ResponseEntity.ok(Map.of("message", message, "running", demoSimulatorService.isRunning()));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        demoSimulatorService.stop();
        return ResponseEntity.ok(Map.of("running", demoSimulatorService.isRunning()));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        return ResponseEntity.ok(Map.of(
                "running", demoSimulatorService.isRunning(),
                "simulatedVehicles", demoSimulatorService.simulatedVehicleCount()
        ));
    }

    @PostMapping("/trigger-hazard")
    public ResponseEntity<Void> triggerHazard(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        demoSimulatorService.triggerHazard();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/trigger-emergency")
    public ResponseEntity<Void> triggerEmergency(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        demoSimulatorService.triggerEmergency();
        return ResponseEntity.accepted().build();
    }

    private void requireAdmin(String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Only admins can control the demo simulator");
        }
    }
}
