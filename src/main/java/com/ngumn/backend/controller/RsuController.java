package com.ngumn.backend.controller;

import com.ngumn.backend.entity.RsuNode;
import com.ngumn.backend.repository.RsuNodeRepository;
import com.ngumn.backend.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Simulated Roadside Unit (RSU) nodes. In this prototype an RSU is a
 * software concept in the backend (no physical government RSU hardware
 * is implied). A real/software RSU "checks in" by pinging its endpoint.
 */
@RestController
@RequestMapping("/api/rsu")
public class RsuController {

    private final RsuNodeRepository rsuNodeRepository;
    private final AuthService authService;

    public RsuController(RsuNodeRepository rsuNodeRepository, AuthService authService) {
        this.rsuNodeRepository = rsuNodeRepository;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<List<RsuNode>> list(@RequestHeader("Authorization") String authorization) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(rsuNodeRepository.findAll());
    }

    @PostMapping("/{rsuCode}/ping")
    public ResponseEntity<RsuNode> ping(@PathVariable String rsuCode,
                                         @RequestParam(required = false) Double latitude,
                                         @RequestParam(required = false) Double longitude) {
        RsuNode node = rsuNodeRepository.findByRsuCode(rsuCode).orElseGet(() -> RsuNode.builder()
                .rsuCode(rsuCode)
                .name("RSU " + rsuCode)
                .latitude(latitude != null ? latitude : 0.0)
                .longitude(longitude != null ? longitude : 0.0)
                .online(true)
                .isSimulated(true)
                .build());
        node.setOnline(true);
        node.setLastPing(LocalDateTime.now());
        if (latitude != null) node.setLatitude(latitude);
        if (longitude != null) node.setLongitude(longitude);
        return ResponseEntity.ok(rsuNodeRepository.save(node));
    }
}
