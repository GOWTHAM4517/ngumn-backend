package com.ngumn.backend.controller;

import com.ngumn.backend.dto.AuthorityChannelRequest;
import com.ngumn.backend.dto.AuthorityReplyRequest;
import com.ngumn.backend.dto.FilingRequest;
import com.ngumn.backend.entity.IncidentType;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.service.AdminControlService;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.ComplaintFilingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Official complaint channels (ComplaintFilingService):
 *
 * GET    /api/authority/channels?type=&lat=&lng=     the channels for a kind of complaint (app)
 * GET    /api/incidents/{id}/filings                 where a complaint was filed, and the answers
 * POST   /api/incidents/{id}/filings                 {channelId, method} - called / WhatsApp / SMS / portal from the app
 * PATCH  /api/filings/{id}                           {officialRef} - the official complaint number they were given
 *
 * No login - the reply link in a department's complaint email:
 * GET    /api/authority/reply/{token}
 * POST   /api/authority/reply/{token}                {action: ACCEPT | RESOLVE | REJECT, name, note, officialRef}
 *
 * Admin:
 * GET    /api/admin/authority-channels               (+ the email service's state)
 * POST   /api/admin/authority-channels
 * PUT    /api/admin/authority-channels/{id}
 * DELETE /api/admin/authority-channels/{id}
 */
@RestController
public class AuthorityController {

    private final ComplaintFilingService filings;
    private final AuthService authService;

    public AuthorityController(ComplaintFilingService filings, AuthService authService) {
        this.filings = filings;
        this.authService = authService;
    }

    @GetMapping("/api/authority/channels")
    public ResponseEntity<List<Map<String, Object>>> channels(@RequestHeader("Authorization") String authorization,
                                                              @RequestParam String type,
                                                              @RequestParam(required = false) Double lat,
                                                              @RequestParam(required = false) Double lng) {
        authService.requireUser(authorization);
        IncidentType t;
        try {
            t = IncidentType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown kind of complaint.");
        }
        return ResponseEntity.ok(filings.channelsView(t, lat, lng));
    }

    @GetMapping("/api/incidents/{id}/filings")
    public ResponseEntity<Map<String, Object>> filings(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(filings.view(user, id));
    }

    @PostMapping("/api/incidents/{id}/filings")
    public ResponseEntity<Map<String, Object>> record(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @Valid @RequestBody FilingRequest r) {
        User user = authService.requireUser(authorization);
        if (r.getChannelId() == null) throw ApiException.badRequest("Which channel?");
        return ResponseEntity.ok(filings.record(user, id, r.getChannelId(), r.getMethod()));
    }

    @PatchMapping("/api/filings/{id}")
    public ResponseEntity<Map<String, Object>> reference(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                         @Valid @RequestBody FilingRequest r) {
        User user = authService.requireUser(authorization);
        return ResponseEntity.ok(filings.setReference(user, id, r.getOfficialRef()));
    }

    // ---- the department's reply link (no login: the token is the key)

    @GetMapping("/api/authority/reply/{token}")
    public ResponseEntity<Map<String, Object>> replyView(@PathVariable String token) {
        return ResponseEntity.ok(filings.replyView(token));
    }

    @PostMapping("/api/authority/reply/{token}")
    public ResponseEntity<Map<String, Object>> reply(@PathVariable String token, @Valid @RequestBody AuthorityReplyRequest r) {
        return ResponseEntity.ok(filings.reply(token, r.getAction(), r.getName(), r.getNote(), r.getOfficialRef()));
    }

    // ---- admin

    @GetMapping("/api/admin/authority-channels")
    public ResponseEntity<Map<String, Object>> adminList(@RequestHeader("Authorization") String authorization) {
        AdminControlService.requireAdmin(authService.requireUser(authorization));
        return ResponseEntity.ok(Map.of("channels", filings.adminList(), "email", filings.describe()));
    }

    @PostMapping("/api/admin/authority-channels")
    public ResponseEntity<Map<String, Object>> adminAdd(@RequestHeader("Authorization") String authorization,
                                                        @Valid @RequestBody AuthorityChannelRequest r) {
        AdminControlService.requireAdmin(authService.requireUser(authorization));
        return ResponseEntity.ok(filings.adminSave(null, r));
    }

    @PutMapping("/api/admin/authority-channels/{id}")
    public ResponseEntity<Map<String, Object>> adminSave(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                         @Valid @RequestBody AuthorityChannelRequest r) {
        AdminControlService.requireAdmin(authService.requireUser(authorization));
        return ResponseEntity.ok(filings.adminSave(id, r));
    }

    @DeleteMapping("/api/admin/authority-channels/{id}")
    public ResponseEntity<Map<String, Object>> adminDelete(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        AdminControlService.requireAdmin(authService.requireUser(authorization));
        filings.adminDelete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
