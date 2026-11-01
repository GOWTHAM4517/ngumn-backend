package com.ngumn.backend.controller;

import com.ngumn.backend.dto.AdminIncidentAction;
import com.ngumn.backend.dto.AdminUserUpdate;
import com.ngumn.backend.dto.BroadcastRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.entity.ResponderTeam;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AdminControlService;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.IncidentService;
import com.ngumn.backend.service.SosService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin dashboard - see and control everything (admin accounts only):
 *
 * GET  /api/admin/all-incidents?team=&status=&kind=help|complaints|all&days=
 * POST /api/admin/all-incidents/{id}          assign / resolve / close / reopen
 * GET  /api/admin/users?q=&filter=            every account
 * POST /api/admin/users/{id}                  disable, role, team, password reset
 * POST /api/admin/users/{id}/approve|reject   "I'm police / ambulance..." requests
 * GET  /api/admin/sos?days=                   crash / SOS alerts and who was reached
 * POST /api/admin/sos/{id}/close
 * POST /api/admin/broadcast                   message to everyone / an area
 * GET  /api/admin/channels                    SMS / call / authority forwarding status
 * POST /api/admin/channels/test               test SMS or call
 */
@RestController
@RequestMapping("/api/admin")
public class AdminControlController {

    private final AdminControlService adminService;
    private final IncidentService incidentService;
    private final SosService sosService;
    private final AuthService authService;

    public AdminControlController(AdminControlService adminService, IncidentService incidentService,
                                  SosService sosService, AuthService authService) {
        this.adminService = adminService;
        this.incidentService = incidentService;
        this.sosService = sosService;
        this.authService = authService;
    }

    private User admin(String authorization) {
        User u = authService.requireUser(authorization);
        AdminControlService.requireAdmin(u);
        return u;
    }

    @GetMapping("/all-incidents")
    public ResponseEntity<List<IncidentResponse>> incidents(@RequestHeader("Authorization") String authorization,
                                                            @RequestParam(required = false) String team,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "all") String kind,
                                                            @RequestParam(defaultValue = "7") int days) {
        User admin = admin(authorization);
        ResponderTeam t = team == null || team.isBlank() ? null : ResponderTeam.valueOf(team.trim().toUpperCase(Locale.ROOT));
        String k = kind.toLowerCase(Locale.ROOT);
        return ResponseEntity.ok(incidentService.adminList(admin, t, status, !k.equals("help"), !k.equals("complaints"), days));
    }

    @PostMapping("/all-incidents/{id}")
    public ResponseEntity<IncidentResponse> act(@RequestHeader("Authorization") String authorization,
                                                @PathVariable Long id, @RequestBody AdminIncidentAction action) {
        return ResponseEntity.ok(incidentService.adminAct(admin(authorization), id, action));
    }

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> users(@RequestHeader("Authorization") String authorization,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(defaultValue = "all") String filter) {
        return ResponseEntity.ok(adminService.users(admin(authorization), q, filter));
    }

    @PostMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@RequestHeader("Authorization") String authorization,
                                                          @PathVariable Long id, @RequestBody AdminUserUpdate update) {
        return ResponseEntity.ok(adminService.update(admin(authorization), id, update));
    }

    @PostMapping("/users/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestHeader("Authorization") String authorization,
                                                       @PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(adminService.decide(admin(authorization), id, true, body != null ? body.get("unitName") : null));
    }

    @PostMapping("/users/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(adminService.decide(admin(authorization), id, false, null));
    }

    @GetMapping("/sos")
    public ResponseEntity<List<Map<String, Object>>> sos(@RequestHeader("Authorization") String authorization,
                                                         @RequestParam(defaultValue = "7") int days, HttpServletRequest http) {
        return ResponseEntity.ok(sosService.recent(admin(authorization), days, SosController.baseOf(http)));
    }

    @PostMapping("/sos/{id}/close")
    public ResponseEntity<Map<String, Object>> closeSos(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable Long id, HttpServletRequest http) {
        return ResponseEntity.ok(sosService.close(admin(authorization), id, SosController.baseOf(http)));
    }

    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(@RequestHeader("Authorization") String authorization,
                                                         @Valid @RequestBody BroadcastRequest request) {
        return ResponseEntity.ok(adminService.broadcast(admin(authorization), request));
    }

    @GetMapping("/channels")
    public ResponseEntity<Map<String, Object>> channels(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(adminService.channels(admin(authorization)));
    }

    @PostMapping("/channels/test")
    public ResponseEntity<Map<String, Object>> test(@RequestHeader("Authorization") String authorization,
                                                    @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminService.test(admin(authorization), body.get("phone"), "CALL".equalsIgnoreCase(body.get("channel"))));
    }
}
