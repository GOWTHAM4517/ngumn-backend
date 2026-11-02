package com.ngumn.backend.controller;

import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.HotspotService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** GET /api/hotspots?lat=&lng=&radiusMeters= - accident black spots near a point (see HotspotService). */
@RestController
public class HotspotController {

    private final HotspotService hotspotService;
    private final AuthService authService;

    public HotspotController(HotspotService hotspotService, AuthService authService) {
        this.hotspotService = hotspotService;
        this.authService = authService;
    }

    @GetMapping("/api/hotspots")
    public ResponseEntity<List<Map<String, Object>>> near(@RequestHeader("Authorization") String authorization,
                                                          @RequestParam double lat, @RequestParam double lng,
                                                          @RequestParam(defaultValue = "5000") double radiusMeters) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(hotspotService.near(lat, lng, radiusMeters));
    }
}
