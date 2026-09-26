package com.ngumn.backend.controller;

import com.ngumn.backend.dto.RuleZoneRequest;
import com.ngumn.backend.dto.RuleZoneResponse;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.TrafficRuleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Place-based traffic rules: speed-limit zones, one-way roads and
 * no-entry zones. Everyone can read them (the app's Drive Guard needs
 * them); only admins can add or remove them.
 */
@RestController
@RequestMapping("/api/rules")
public class RuleZoneController {

    private final TrafficRuleService trafficRuleService;
    private final AuthService authService;

    public RuleZoneController(TrafficRuleService trafficRuleService, AuthService authService) {
        this.trafficRuleService = trafficRuleService;
        this.authService = authService;
    }

    @GetMapping("/zones")
    public ResponseEntity<List<RuleZoneResponse>> zones(@RequestHeader("Authorization") String authorization,
                                                         @RequestParam(required = false) Double lat,
                                                         @RequestParam(required = false) Double lng,
                                                         @RequestParam(defaultValue = "50") double radiusKm) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(trafficRuleService.zonesNear(lat, lng, radiusKm));
    }

    @PostMapping("/zones")
    public ResponseEntity<RuleZoneResponse> create(@RequestHeader("Authorization") String authorization,
                                                   @Valid @RequestBody RuleZoneRequest request) {
        requireAdmin(authorization);
        return ResponseEntity.ok(trafficRuleService.createZone(request));
    }

    @DeleteMapping("/zones/{kind}/{id}")
    public ResponseEntity<Void> delete(@RequestHeader("Authorization") String authorization,
                                       @PathVariable String kind,
                                       @PathVariable Long id) {
        requireAdmin(authorization);
        trafficRuleService.deleteZone(kind, id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) {
            throw ApiException.forbidden("Only admins can change traffic rule zones");
        }
    }
}
