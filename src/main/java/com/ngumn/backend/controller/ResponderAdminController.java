package com.ngumn.backend.controller;

import com.ngumn.backend.dto.ResponderCreateRequest;
import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.IncidentService;
import com.ngumn.backend.service.ResponderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin dashboard > Responders: police, ambulance and department accounts (see ResponderService). */
@RestController
@RequestMapping("/api/admin")
public class ResponderAdminController {

    private final ResponderService responderService;
    private final IncidentService incidentService;
    private final AuthService authService;

    public ResponderAdminController(ResponderService responderService, IncidentService incidentService, AuthService authService) {
        this.responderService = responderService;
        this.incidentService = incidentService;
        this.authService = authService;
    }

    @GetMapping("/responders")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(responderService.list(authService.requireUser(authorization)));
    }

    @PostMapping("/responders")
    public ResponseEntity<Map<String, Object>> save(@RequestHeader("Authorization") String authorization,
                                                    @Valid @RequestBody ResponderCreateRequest request) {
        return ResponseEntity.ok(responderService.createOrUpdate(authService.requireUser(authorization), request));
    }

    @DeleteMapping("/responders/{id}")
    public ResponseEntity<Map<String, Object>> remove(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(responderService.remove(authService.requireUser(authorization), id));
    }

    /** Help requests waiting per team, for the dashboard tiles. */
    @GetMapping("/incidents/open")
    public ResponseEntity<Map<String, Long>> openIncidents(@RequestHeader("Authorization") String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can view this.");
        return ResponseEntity.ok(incidentService.openCounts());
    }
}
