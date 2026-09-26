package com.ngumn.backend.controller;

import com.ngumn.backend.dto.ViolationRequest;
import com.ngumn.backend.dto.ViolationResponse;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.TrafficRuleService;
import com.ngumn.backend.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Traffic violations - a driver's own record (/mine), reports from Drive
 * Guard on the phone (POST), and the network-wide list for admins.
 */
@RestController
@RequestMapping("/api/violations")
public class ViolationController {

    private final TrafficRuleService trafficRuleService;
    private final VehicleService vehicleService;
    private final AuthService authService;

    public ViolationController(TrafficRuleService trafficRuleService, VehicleService vehicleService,
                               AuthService authService) {
        this.trafficRuleService = trafficRuleService;
        this.vehicleService = vehicleService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<ViolationResponse> report(@RequestHeader("Authorization") String authorization,
                                                    @Valid @RequestBody ViolationRequest request) {
        User user = authService.requireUser(authorization);
        Vehicle vehicle = vehicleService.getOwnedVehicleOrThrow(request.getVehicleId(), user.getId());
        return ResponseEntity.ok(trafficRuleService.reportFromDevice(user, vehicle, request));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ViolationResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(trafficRuleService.mine(user.getId()));
    }

    @GetMapping
    public ResponseEntity<List<ViolationResponse>> recent(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Only admins can see the whole network's violations");
        }
        return ResponseEntity.ok(trafficRuleService.recent());
    }
}
