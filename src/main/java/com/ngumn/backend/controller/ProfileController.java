package com.ngumn.backend.controller;

import com.ngumn.backend.dto.ContactsSaveRequest;
import com.ngumn.backend.dto.ResponderAccessRequest;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.ContactService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The signed-in person's own things (see ContactService):
 *
 * GET  /api/contacts                - my emergency contacts
 * PUT  /api/contacts                - replace them (3 to 5, calling order)
 * GET  /api/me/profile              - my phone, responder request, contact count
 * PUT  /api/me/phone                - my own number ({"phone": "..."}; blank clears)
 * POST /api/me/responder-request    - "I'm police / ambulance / fire / department" (admin approves)
 * DELETE /api/me/responder-request  - withdraw it
 */
@RestController
public class ProfileController {

    private final ContactService contactService;
    private final AuthService authService;

    public ProfileController(ContactService contactService, AuthService authService) {
        this.contactService = contactService;
        this.authService = authService;
    }

    @GetMapping("/api/contacts")
    public ResponseEntity<List<Map<String, Object>>> contacts(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(contactService.list(authService.requireUser(authorization)));
    }

    @PutMapping("/api/contacts")
    public ResponseEntity<List<Map<String, Object>>> save(@RequestHeader("Authorization") String authorization,
                                                          @Valid @RequestBody ContactsSaveRequest request) {
        return ResponseEntity.ok(contactService.save(authService.requireUser(authorization), request.getContacts()));
    }

    @GetMapping("/api/me/profile")
    public ResponseEntity<Map<String, Object>> profile(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(contactService.profile(authService.requireUser(authorization)));
    }

    @PutMapping("/api/me/phone")
    public ResponseEntity<Map<String, Object>> phone(@RequestHeader("Authorization") String authorization,
                                                     @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(contactService.setPhone(authService.requireUser(authorization), body.get("phone")));
    }

    @PostMapping("/api/me/responder-request")
    public ResponseEntity<Map<String, Object>> requestAccess(@RequestHeader("Authorization") String authorization,
                                                             @Valid @RequestBody ResponderAccessRequest request) {
        return ResponseEntity.ok(contactService.requestResponder(authService.requireUser(authorization), request));
    }

    @DeleteMapping("/api/me/responder-request")
    public ResponseEntity<Map<String, Object>> withdraw(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(contactService.withdrawRequest(authService.requireUser(authorization)));
    }
}
