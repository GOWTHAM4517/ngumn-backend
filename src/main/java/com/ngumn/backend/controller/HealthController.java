package com.ngumn.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A plain, unauthenticated "is the server up" endpoint.
 *
 * Two things need this once the backend is hosted rather than run on a
 * laptop: the host's own health check (e.g. Render's "Health Check Path"
 * setting), and an external uptime pinger (e.g. UptimeRobot) hitting it
 * every few minutes so a free always-on host never sees the app go idle
 * and spin it down. Deliberately outside /api so it needs no
 * Authorization header and never touches the database.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "time", LocalDateTime.now().toString()));
    }
}
