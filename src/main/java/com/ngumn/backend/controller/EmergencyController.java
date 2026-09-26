package com.ngumn.backend.controller;

import com.ngumn.backend.dto.EmergencyEventResponse;
import com.ngumn.backend.dto.EmergencyStartRequest;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.EmergencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    private final EmergencyService emergencyService;
    private final AuthService authService;

    public EmergencyController(EmergencyService emergencyService, AuthService authService) {
        this.emergencyService = emergencyService;
        this.authService = authService;
    }

    @PostMapping("/start")
    public ResponseEntity<EmergencyEventResponse> start(@RequestHeader("Authorization") String authorization,
                                                          @Valid @RequestBody EmergencyStartRequest request) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(EmergencyEventResponse.from(emergencyService.start(request)));
    }

    @PostMapping("/{id}/end")
    public ResponseEntity<EmergencyEventResponse> end(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable Long id) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(EmergencyEventResponse.from(emergencyService.end(id)));
    }

    @GetMapping("/active")
    public ResponseEntity<List<EmergencyEventResponse>> active(@RequestHeader("Authorization") String authorization) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(emergencyService.active().stream()
                .map(EmergencyEventResponse::from).collect(Collectors.toList()));
    }
}
