package com.ngumn.backend.controller;

import com.ngumn.backend.dto.BackupRequest;
import com.ngumn.backend.dto.HospitalRequest;
import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.dto.IncidentUpdateRequest;
import com.ngumn.backend.dto.RatingRequest;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.ComplaintFilingService;
import com.ngumn.backend.service.IncidentService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ComplaintFilingService filings;

    public IncidentController(IncidentService incidentService, AuthService authService, ComplaintFilingService filings) {
        this.incidentService = incidentService;
        this.authService = authService;
        this.filings = filings;
    }

    /**
     * Ask for help, or complain. Returns the request - two for an accident with
     * people hurt (police + ambulance). A complaint is also emailed to the
     * official channels that have an address for it (ComplaintFilingService).
     */
    @PostMapping
    public ResponseEntity<List<IncidentResponse>> create(@RequestHeader("Authorization") String authorization,
                                                         @Valid @RequestBody IncidentRequest request, HttpServletRequest http) {
        User user = authService.requireUser(authorization);
        List<IncidentResponse> made = incidentService.create(user, request);
        try {
            filings.fileNew(user, made.stream().map(IncidentResponse::getId).toList(), SosController.baseOf(http));
        } catch (RuntimeException e) {
            // The complaint is made either way; its screen shows what was filed.
            org.slf4j.LoggerFactory.getLogger(IncidentController.class).warn("Official filing failed: {}", e.toString());
        }
        return ResponseEntity.ok(made);
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

    /** A responder on it asks another team (or unit) to come too. */
    @PostMapping("/{id}/backup")
    public ResponseEntity<IncidentResponse> backup(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody BackupRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.backup(user, id, request));
    }

    /** Ambulance: which hospital it's taking the person to. */
    @PostMapping("/{id}/hospital")
    public ResponseEntity<IncidentResponse> hospital(@RequestHeader("Authorization") String authorization,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody HospitalRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.hospital(user, id, request));
    }

    /** The person who asked rates a resolved request or complaint. */
    @PostMapping("/{id}/rate")
    public ResponseEntity<IncidentResponse> rate(@RequestHeader("Authorization") String authorization,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody RatingRequest request) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.rate(user, id, request));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<IncidentResponse> cancel(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(incidentService.cancel(user, id));
    }
}
