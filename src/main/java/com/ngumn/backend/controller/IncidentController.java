package com.ngumn.backend.controller;

import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.dto.IncidentUpdateRequest;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.IncidentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Help requests (see IncidentService).
 *
 * Anyone: POST /api/incidents (ask), GET /api/incidents/mine,
 * POST /api/incidents/{id}/cancel.
 * Responders: GET /api/incidents/queue, POST /api/incidents/{id}/accept,
 * POST /api/incidents/{id}/status.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final AuthService authService;

    public IncidentController(IncidentService incidentService, AuthService authService) {
        this.incidentService = incidentService;
        this.authService = authService;
    }

    /** Ask for help. Returns the request - two for an accident with people hurt (police + ambulance). */
    @PostMapping
    public ResponseEntity<List<IncidentResponse>> create(@RequestHeader("Authorization") String authorization,
                                                         @Valid @RequestBody IncidentRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.create(user, request));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<IncidentResponse>> mine(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.mine(user));
    }

    /** A responder's list; lat / lng (where they are) adds each request's distance. */
    @GetMapping("/queue")
    public ResponseEntity<List<IncidentResponse>> queue(@RequestHeader("Authorization") String authorization,
                                                        @RequestParam(required = false) Double lat,
                                                        @RequestParam(required = false) Double lng) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.queue(user, lat, lng));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentResponse> one(@RequestHeader("Authorization") String authorization,
                                                @PathVariable Long id,
                                                @RequestParam(required = false) Double lat,
                                                @RequestParam(required = false) Double lng) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.get(user, id, lat, lng));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<IncidentResponse> accept(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id,
                                                   @RequestParam(required = false) Double lat,
                                                   @RequestParam(required = false) Double lng) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.accept(user, id, lat, lng));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<IncidentResponse> status(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody IncidentUpdateRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.update(user, id, request));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<IncidentResponse> cancel(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.cancel(user, id));
    }
}
