package com.ngumn.backend.controller;

import com.ngumn.backend.dto.CarPointRequest;
import com.ngumn.backend.dto.CarRequest;
import com.ngumn.backend.dto.CarTransferRequest;
import com.ngumn.backend.dto.TestDriveRequest;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.CarGuardService;
import com.ngumn.backend.service.TrackerFormats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Car guard (see CarGuardService).
 *
 * <pre>
 * GET    /api/cars                      my cars + fleet counts
 * POST   /api/cars                      add a car
 * GET    /api/cars/{id}                 one car
 * PATCH  /api/cars/{id}                 change name / plate / home / speed alerts / tracker id
 * DELETE /api/cars/{id}
 * GET    /api/cars/{id}/trail?hours=24  positions + trips
 * POST   /api/cars/{id}/stolen          report stolen (body: where it was, optional)
 * DELETE /api/cars/{id}/stolen          found it
 * POST   /api/cars/{id}/test-drive      start a customer's test drive
 * POST   /api/cars/{id}/test-drive/end
 * GET    /api/cars/{id}/test-drives     last 20
 * POST   /api/cars/{id}/transfer        hand a sold car to the buyer's account
 * POST   /api/cars/{id}/position        "try it with this phone" (owner's phone as the tracker)
 * GET    /api/cars/{id}/lookout         anyone: a stolen car's description (the "I saw it" screen)
 * POST   /api/cars/{id}/sighting        anyone: "I saw it here"
 * GET    /api/car-track/{token}         public: the police's live page (/car.html?t=)
 * GET    /api/admin/cars/stolen         admin: every stolen car
 * GET|POST /api/tracker                 public: GPS trackers send positions here (device id = secret)
 * </pre>
 */
@RestController
public class CarGuardController {

    private final CarGuardService carGuard;
    private final AuthService authService;

    public CarGuardController(CarGuardService carGuard, AuthService authService) {
        this.carGuard = carGuard;
        this.authService = authService;
    }

    @GetMapping("/api/cars")
    public ResponseEntity<Map<String, Object>> list(@RequestHeader("Authorization") String authorization, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.list(authService.requireUser(authorization), SosController.baseOf(http)));
    }

    @PostMapping("/api/cars")
    public ResponseEntity<Map<String, Object>> add(@RequestHeader("Authorization") String authorization,
                                                   @Valid @RequestBody CarRequest r, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.add(authService.requireUser(authorization), r, SosController.baseOf(http)));
    }

    @GetMapping("/api/cars/{id}")
    public ResponseEntity<Map<String, Object>> get(@RequestHeader("Authorization") String authorization,
                                                   @PathVariable Long id, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.get(authService.requireUser(authorization), id, SosController.baseOf(http)));
    }

    @PatchMapping("/api/cars/{id}")
    public ResponseEntity<Map<String, Object>> update(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @Valid @RequestBody CarRequest r, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.update(authService.requireUser(authorization), id, r, SosController.baseOf(http)));
    }

    @DeleteMapping("/api/cars/{id}")
    public ResponseEntity<Map<String, Object>> delete(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        carGuard.delete(authService.requireUser(authorization), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/api/cars/{id}/trail")
    public ResponseEntity<Map<String, Object>> trail(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                     @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(carGuard.trail(authService.requireUser(authorization), id, hours));
    }

    @PostMapping("/api/cars/{id}/stolen")
    public ResponseEntity<Map<String, Object>> stolen(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @RequestBody(required = false) CarPointRequest where, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.reportStolen(authService.requireUser(authorization), id, where, SosController.baseOf(http)));
    }

    @DeleteMapping("/api/cars/{id}/stolen")
    public ResponseEntity<Map<String, Object>> found(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                     HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.recovered(authService.requireUser(authorization), id, SosController.baseOf(http)));
    }

    @PostMapping("/api/cars/{id}/test-drive")
    public ResponseEntity<Map<String, Object>> startDrive(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                          @Valid @RequestBody TestDriveRequest r) {
        return ResponseEntity.ok(carGuard.startTestDrive(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/cars/{id}/test-drive/end")
    public ResponseEntity<Map<String, Object>> endDrive(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(carGuard.endTestDrive(authService.requireUser(authorization), id));
    }

    @GetMapping("/api/cars/{id}/test-drives")
    public ResponseEntity<List<Map<String, Object>>> drives(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(carGuard.testDrives(authService.requireUser(authorization), id));
    }

    @PostMapping("/api/cars/{id}/transfer")
    public ResponseEntity<Map<String, Object>> transfer(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                        @Valid @RequestBody CarTransferRequest r) {
        return ResponseEntity.ok(carGuard.transfer(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/cars/{id}/position")
    public ResponseEntity<Map<String, Object>> phoneFix(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                        @Valid @RequestBody CarPointRequest r) {
        return ResponseEntity.ok(carGuard.phoneFix(authService.requireUser(authorization), id, r));
    }

    @GetMapping("/api/cars/{id}/lookout")
    public ResponseEntity<Map<String, Object>> lookout(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(carGuard.lookout(authService.requireUser(authorization), id));
    }

    @PostMapping("/api/cars/{id}/sighting")
    public ResponseEntity<Map<String, Object>> sighting(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                        @Valid @RequestBody CarPointRequest r) {
        return ResponseEntity.ok(carGuard.sighting(authService.requireUser(authorization), id, r));
    }

    @GetMapping("/api/car-track/{token}")
    public ResponseEntity<Map<String, Object>> publicView(@PathVariable String token) {
        return ResponseEntity.ok(carGuard.publicView(token));
    }

    @GetMapping("/api/admin/cars/stolen")
    public ResponseEntity<List<Map<String, Object>>> stolenCars(@RequestHeader("Authorization") String authorization, HttpServletRequest http) {
        return ResponseEntity.ok(carGuard.stolenForAdmin(authService.requireUser(authorization), SosController.baseOf(http)));
    }

    /**
     * Trackers: OsmAnd / Traccar Client parameters, Traccar Client JSON, or a
     * Traccar server forwarding positions (see TrackerFormats). No sign-in -
     * the device id is the secret.
     */
    @RequestMapping(value = "/api/tracker", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> tracker(HttpServletRequest http, @RequestBody(required = false) String body) {
        Map<String, String> params = new HashMap<>();
        http.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) params.put(k, v[0]);
        });
        return ResponseEntity.ok(carGuard.ingest(TrackerFormats.parse(params, body)));
    }
}
