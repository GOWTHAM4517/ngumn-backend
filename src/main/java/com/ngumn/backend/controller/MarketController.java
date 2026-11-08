package com.ngumn.backend.controller;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.ListingPhoto;
import com.ngumn.backend.service.AuthService;
import com.ngumn.backend.service.MarketService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Buy &amp; Sell (see MarketService).
 *
 * <pre>
 * GET    /api/market/listings?lat=&lng=&radiusKm=&type=&q=&minPrice=&maxPrice=&sellerType=&fuel=
 *                             &transmission=&minYear=&maxKm=&condition=&sort=&offset=   vehicles for sale
 * POST   /api/market/listings                   put one up for sale
 * GET    /api/market/listings/{id}              one (its seller also gets the test-drive requests)
 * PATCH  /api/market/listings/{id}              change it
 * POST   /api/market/listings/{id}/status       {status: ACTIVE | RESERVED | WITHDRAWN}
 * POST   /api/market/listings/{id}/sold         sold (to a test-drive buyer, a Raksio account, or outside)
 * POST   /api/market/listings/{id}/photos       add a photo (base64)
 * DELETE /api/market/listings/{id}/photos/{token}
 * POST   /api/market/listings/{id}/report       fake / scam / stolen...
 * POST   /api/market/listings/{id}/bookings     buyer: ask for a test drive
 * POST   /api/market/listings/{id}/walk-in      seller: a walk-in customer's test drive, starting now
 * GET    /api/market/mine                       my vehicles, my test drives (buyer and seller)
 * GET    /api/market/check?plate=               is this number reported stolen?
 * GET    /api/market/photo/{token}              public: a photo
 * GET    /api/market/bookings/{id}              a test drive
 * POST   /api/market/bookings/{id}/accept | decline | propose | agree | cancel | start | end | position | rate
 * GET    /api/admin/market/reported             admin: reported and hidden vehicles
 * POST   /api/admin/market/listings/{id}        admin: {action: RESTORE | REMOVE}
 * </pre>
 */
@RestController
public class MarketController {

    private final MarketService market;
    private final AuthService authService;

    public MarketController(MarketService market, AuthService authService) {
        this.market = market;
        this.authService = authService;
    }

    @GetMapping("/api/market/listings")
    public ResponseEntity<Map<String, Object>> browse(@RequestHeader("Authorization") String authorization,
                                                      @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                                                      @RequestParam(required = false) Double radiusKm, @RequestParam(required = false) String type,
                                                      @RequestParam(required = false) String q, @RequestParam(required = false) Long minPrice,
                                                      @RequestParam(required = false) Long maxPrice, @RequestParam(required = false) String sellerType,
                                                      @RequestParam(required = false) String fuel, @RequestParam(required = false) String transmission,
                                                      @RequestParam(required = false) Integer minYear, @RequestParam(required = false) Integer maxKm,
                                                      @RequestParam(required = false) String condition, @RequestParam(required = false) String sort,
                                                      @RequestParam(required = false) Integer offset) {
        return ResponseEntity.ok(market.browse(authService.requireUser(authorization), new MarketService.Filters(lat, lng, radiusKm, type, q,
                minPrice, maxPrice, sellerType, fuel, transmission, minYear, maxKm, condition, sort, offset)));
    }

    @PostMapping("/api/market/listings")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader("Authorization") String authorization, @Valid @RequestBody ListingRequest r) {
        return ResponseEntity.ok(market.create(authService.requireUser(authorization), r));
    }

    @GetMapping("/api/market/listings/{id}")
    public ResponseEntity<Map<String, Object>> get(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(market.detail(authService.requireUser(authorization), id));
    }

    @PatchMapping("/api/market/listings/{id}")
    public ResponseEntity<Map<String, Object>> update(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @Valid @RequestBody ListingRequest r) {
        return ResponseEntity.ok(market.update(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/market/listings/{id}/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(market.setStatus(authService.requireUser(authorization), id, body.get("status")));
    }

    @PostMapping("/api/market/listings/{id}/sold")
    public ResponseEntity<Map<String, Object>> sold(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                    @Valid @RequestBody(required = false) SoldRequest r) {
        return ResponseEntity.ok(market.markSold(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/market/listings/{id}/photos")
    public ResponseEntity<Map<String, Object>> addPhoto(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                        @Valid @RequestBody PhotoRequest r) {
        return ResponseEntity.ok(market.addPhoto(authService.requireUser(authorization), id, r));
    }

    @DeleteMapping("/api/market/listings/{id}/photos/{token}")
    public ResponseEntity<Map<String, Object>> removePhoto(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                           @PathVariable String token) {
        return ResponseEntity.ok(market.removePhoto(authService.requireUser(authorization), id, token));
    }

    @PostMapping("/api/market/listings/{id}/report")
    public ResponseEntity<Map<String, Object>> report(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @Valid @RequestBody ListingReportRequest r) {
        return ResponseEntity.ok(market.report(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/market/listings/{id}/bookings")
    public ResponseEntity<Map<String, Object>> book(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                    @Valid @RequestBody BookingRequest r) {
        return ResponseEntity.ok(market.book(authService.requireUser(authorization), id, r));
    }

    @PostMapping("/api/market/listings/{id}/walk-in")
    public ResponseEntity<Map<String, Object>> walkIn(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                      @Valid @RequestBody BookingRequest r) {
        return ResponseEntity.ok(market.walkIn(authService.requireUser(authorization), id, r));
    }

    @GetMapping("/api/market/mine")
    public ResponseEntity<Map<String, Object>> mine(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(market.mine(authService.requireUser(authorization)));
    }

    @GetMapping("/api/market/check")
    public ResponseEntity<Map<String, Object>> check(@RequestHeader("Authorization") String authorization, @RequestParam String plate) {
        return ResponseEntity.ok(market.checkPlate(authService.requireUser(authorization), plate));
    }

    @GetMapping("/api/market/photo/{token}")
    public ResponseEntity<byte[]> photo(@PathVariable String token) {
        ListingPhoto p = market.photo(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(p.getData());
    }

    @GetMapping("/api/market/bookings/{id}")
    public ResponseEntity<Map<String, Object>> booking(@RequestHeader("Authorization") String authorization, @PathVariable Long id) {
        return ResponseEntity.ok(market.booking(authService.requireUser(authorization), id));
    }

    @PostMapping("/api/market/bookings/{id}/{action}")
    public ResponseEntity<Map<String, Object>> act(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                   @PathVariable String action, @RequestBody(required = false) Map<String, Object> body) {
        var user = authService.requireUser(authorization);
        return ResponseEntity.ok(switch (action) {
            case "accept" -> market.accept(user, id);
            case "decline" -> market.decline(user, id, actionBody(body));
            case "propose" -> market.propose(user, id, actionBody(body));
            case "agree" -> market.agree(user, id);
            case "cancel" -> market.cancel(user, id, actionBody(body));
            case "start" -> market.start(user, id, actionBody(body));
            case "end" -> market.end(user, id);
            case "rate" -> market.rate(user, id, actionBody(body));
            case "position" -> market.phoneFix(user, id, pointBody(body));
            default -> throw com.ngumn.backend.exception.ApiException.notFound("Unknown action");
        });
    }

    @GetMapping("/api/admin/market/reported")
    public ResponseEntity<List<Map<String, Object>>> reported(@RequestHeader("Authorization") String authorization) {
        return ResponseEntity.ok(market.reportedForAdmin(authService.requireUser(authorization)));
    }

    @PostMapping("/api/admin/market/listings/{id}")
    public ResponseEntity<Map<String, Object>> adminAction(@RequestHeader("Authorization") String authorization, @PathVariable Long id,
                                                           @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(market.adminAction(authService.requireUser(authorization), id, body.get("action")));
    }

    // One endpoint for the booking actions, so the bodies are read by hand (with the same limits as the DTOs).

    static BookingActionRequest actionBody(Map<String, Object> body) {
        BookingActionRequest r = new BookingActionRequest();
        if (body == null) return r;
        r.setReason(cut(str(body.get("reason")), 200));
        r.setCode(cut(str(body.get("code")), 4));
        r.setComment(cut(str(body.get("comment")), 300));
        r.setLatitude(num(body.get("latitude")));
        r.setLongitude(num(body.get("longitude")));
        Double stars = num(body.get("stars"));
        if (stars != null) {
            if (stars < 1 || stars > 5) throw com.ngumn.backend.exception.ApiException.badRequest("Give 1 to 5 stars.");
            r.setStars(stars.intValue());
        }
        if (body.get("asDescribed") instanceof Boolean b) r.setAsDescribed(b);
        String at = str(body.get("at"));
        if (at != null) {
            try {
                r.setAt(java.time.LocalDateTime.parse(at.length() > 19 ? at.substring(0, 19) : at));
            } catch (java.time.format.DateTimeParseException e) {
                throw com.ngumn.backend.exception.ApiException.badRequest("That time doesn't look right.");
            }
        }
        return r;
    }

    static CarPointRequest pointBody(Map<String, Object> body) {
        CarPointRequest p = new CarPointRequest();
        if (body == null) return p;
        p.setLatitude(num(body.get("latitude")));
        p.setLongitude(num(body.get("longitude")));
        p.setSpeedKmh(num(body.get("speedKmh")));
        p.setHeading(num(body.get("heading")));
        return p;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
