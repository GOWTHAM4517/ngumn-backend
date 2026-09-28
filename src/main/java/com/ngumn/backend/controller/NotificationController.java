package com.ngumn.backend.controller;

import com.ngumn.backend.dto.NotificationSettings;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.NotificationSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * What you're told about in the area around you - the app's Settings >
 * Notifications. GET returns your settings (defaults filled in); PUT
 * changes any of them.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationSettingsService settingsService;
    private final AuthService authService;

    public NotificationController(NotificationSettingsService settingsService, AuthService authService) {
        this.settingsService = settingsService;
        this.authService = authService;
    }

    @GetMapping("/settings")
    public ResponseEntity<NotificationSettings> get(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(settingsService.get(authService.requireUser(authorization)));
    }

    @PutMapping("/settings")
    public ResponseEntity<NotificationSettings> update(@RequestHeader("Authorization") String authorization,
                                                         @RequestBody NotificationSettings change) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(settingsService.update(user, change));
    }
}
