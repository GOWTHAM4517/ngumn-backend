package com.ngumn.backend.controller;

import com.ngumn.backend.dto.SosRequest;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.SosService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Crash alerts and SOS (see SosService).
 *
 * POST /api/sos                 - crash detected / SOS pressed
 * GET  /api/sos/active          - my alerts still open
 * GET  /api/sos/{id}            - one, with who was reached (SMS / call / in app)
 * POST /api/sos/{id}/location   - where I am now (for the live-location link)
 * POST /api/sos/{id}/cancel     - "I'm OK"
 * GET  /api/track/{token}       - public: what the contact's live-location link shows
 */
@RestController
public class SosController {

    private final SosService sosService;
    private final AuthService authService;

    public SosController(SosService sosService, AuthService authService) {
        this.sosService = sosService;
        this.authService = authService;
    }

    /** The address the app reached this server on - used in links when PUBLIC_URL isn't set. */
    static String baseOf(HttpServletRequest req) {
        String proto = header(req, "X-Forwarded-Proto", req.getScheme());
        String host = header(req, "X-Forwarded-Host", req.getHeader("Host"));
        if (host == null || host.isBlank()) host = req.getServerName() + ":" + req.getServerPort();
        return proto.split(",")[0].trim() + "://" + host.split(",")[0].trim();
    }

    private static String header(HttpServletRequest req, String name, String fallback) {
        String v = req.getHeader(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    @PostMapping("/api/sos")
    public ResponseEntity<Map<String, Object>> trigger(@RequestHeader("Authorization") String authorization,
                                                       @Valid @RequestBody SosRequest request, HttpServletRequest http) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(sosService.trigger(user, request, baseOf(http)));
    }

    @GetMapping("/api/sos/active")
    public ResponseEntity<List<Map<String, Object>>> active(@RequestHeader("Authorization") String authorization, HttpServletRequest http) {
        return ResponseEntity.ok(sosService.active(authService.requireUser(authorization), baseOf(http)));
    }

    @GetMapping("/api/sos/{id}")
    public ResponseEntity<Map<String, Object>> one(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id, HttpServletRequest http) {
        return ResponseEntity.ok(sosService.get(authService.requireUser(authorization), id, baseOf(http)));
    }

    @PostMapping("/api/sos/{id}/location")
    public ResponseEntity<Map<String, Object>> location(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable Long id,
                                                        @RequestParam double lat, @RequestParam double lng,
                                                        HttpServletRequest http) {
        return ResponseEntity.ok(sosService.updateLocation(authService.requireUser(authorization), id, lat, lng, baseOf(http)));
    }

    @PostMapping("/api/sos/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestHeader("Authorization") String authorization,
                                                      @PathVariable Long id, HttpServletRequest http) {
        return ResponseEntity.ok(sosService.cancel(authService.requireUser(authorization), id, baseOf(http)));
    }

    /** No sign-in: the link an emergency contact gets by SMS. */
    @GetMapping("/api/track/{token}")
    public ResponseEntity<Map<String, Object>> track(@PathVariable String token) {
        return ResponseEntity.ok(sosService.track(token));
    }
}
