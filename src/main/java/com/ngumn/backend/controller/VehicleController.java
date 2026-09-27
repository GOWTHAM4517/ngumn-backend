package com.ngumn.backend.controller;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;
    private final AuthService authService;

    public VehicleController(VehicleService vehicleService, AuthService authService) {
        this.vehicleService = vehicleService;
        this.authService = authService;
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> register(@RequestHeader("Authorization") String authorization,
                                                      @Valid @RequestBody VehicleCreateRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(vehicleService.register(user, request));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<VehicleResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(vehicleService.myVehicles(user.getId()));
    }

    /**
     * Vehicles with a live position. With lat / lng, only those within
     * radiusMeters (default 2 km, at most 20 km) of that point, nearest
     * first - what the app asks for, so people only see what's around them.
     */
    @GetMapping
    public ResponseEntity<List<VehicleResponse>> all(@RequestHeader("Authorization") String authorization,
                                                     @RequestParam(required = false) Double lat,
                                                     @RequestParam(required = false) Double lng,
                                                     @RequestParam(required = false) Double radiusMeters) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(vehicleService.activeNear(lat, lng, radiusMeters));
    }

    /** Switch how you're travelling (walking, bike, car...) and/or set your number plate. */
    @PatchMapping("/{id}")
    public ResponseEntity<VehicleResponse> update(@RequestHeader("Authorization") String authorization,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody VehicleUpdateRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(vehicleService.update(user, id, request));
    }

    @PostMapping("/{id}/location")
    public ResponseEntity<VehicleResponse> updateLocation(@RequestHeader("Authorization") String authorization,
                                                            @PathVariable Long id,
                                                            @Valid @RequestBody LocationUpdateRequest request) {
        User user = authService.requireUser(authorization);
        Vehicle vehicle = vehicleService.getOwnedVehicleOrThrow(id, user.getId());
        return ResponseEntity.ok(vehicleService.updateLocation(vehicle, request));
    }

    @GetMapping("/{id}/nearby")
    public ResponseEntity<List<NearbyVehicleResponse>> nearby(@RequestHeader("Authorization") String authorization,
                                                                @PathVariable Long id,
                                                                @RequestParam(defaultValue = "300") double radiusMeters) {
        authService.requireUser(authorization);
        Vehicle vehicle = vehicleService.findByIdOrThrow(id);
        return ResponseEntity.ok(vehicleService.nearby(vehicle, radiusMeters));
    }
}
