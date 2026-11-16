package com.ngumn.backend.controller;

import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.live.LiveDataStatus;
import com.ngumn.backend.live.WeatherService;
import com.ngumn.backend.ml.ReportTrustModel;
import com.ngumn.backend.ml.TrafficSpeedModel;
import com.ngumn.backend.roads.RoadDataService;
import com.ngumn.backend.roads.RoadInfo;
import com.ngumn.backend.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Real roads, live traffic, live weather and the learned models:
 *
 * GET  /api/roads/at?lat&lng[&heading&speedKmh&mode]  the road there and its real limit / one-way rule
 * GET  /api/traffic/live?lat&lng[&radiusKm]           jams and slow roads detected right now
 * GET  /api/traffic/forecast?lat&lng[&radiusKm&at]    roads usually slow at that hour (learned)
 * GET  /api/weather?lat&lng                           live weather and warnings for the nearest town
 * GET  /api/admin/live-data                           everything above for the Control Centre (admin)
 * POST /api/admin/live-data/roads/refresh             download the region's roads again now (admin)
 * POST /api/admin/live-data/weather/refresh           read the weather again now (admin)
 */
@RestController
public class LiveDataController {

    private final AuthService authService;
    private final RoadDataService roads;
    private final TrafficSpeedModel traffic;
    private final ReportTrustModel trust;
    private final WeatherService weather;

    public LiveDataController(AuthService authService, RoadDataService roads, TrafficSpeedModel traffic,
                              ReportTrustModel trust, WeatherService weather) {
        this.authService = authService;
        this.roads = roads;
        this.traffic = traffic;
        this.trust = trust;
        this.weather = weather;
    }

    @GetMapping("/api/roads/at")
    public ResponseEntity<RoadInfo> roadAt(@RequestHeader("Authorization") String authorization,
                                           @RequestParam double lat, @RequestParam double lng,
                                           @RequestParam(required = false) Double heading,
                                           @RequestParam(required = false) Double speedKmh,
                                           @RequestParam(required = false) String mode) {
        authService.requireUser(authorization);
        TravelMode m = TravelMode.CAR;
        if (mode != null) {
            try {
                m = TravelMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("mode must be one of " + Arrays.toString(TravelMode.values()));
            }
        }
        return ResponseEntity.ok(roads.roadAt(lat, lng, heading, speedKmh == null ? 0 : speedKmh, null, m));
    }

    @GetMapping("/api/traffic/live")
    public ResponseEntity<List<Map<String, Object>>> trafficLive(@RequestHeader("Authorization") String authorization,
                                                                 @RequestParam double lat, @RequestParam double lng,
                                                                 @RequestParam(defaultValue = "5") double radiusKm) {
        authService.requireUser(authorization);
        return ResponseEntity.ok(traffic.liveNear(lat, lng, Math.min(30, Math.max(0.5, radiusKm))));
    }

    @GetMapping("/api/traffic/forecast")
    public ResponseEntity<List<Map<String, Object>>> trafficForecast(@RequestHeader("Authorization") String authorization,
                                                                     @RequestParam double lat, @RequestParam double lng,
                                                                     @RequestParam(defaultValue = "5") double radiusKm,
                                                                     @RequestParam(required = false) String at) {
        authService.requireUser(authorization);
        LocalDateTime when = LocalDateTime.now();
        if (at != null && !at.isBlank()) {
            try {
                when = LocalDateTime.parse(at.trim());
            } catch (RuntimeException e) {
                throw ApiException.badRequest("at must look like 2026-11-20T18:00");
            }
        }
        return ResponseEntity.ok(traffic.forecastNear(lat, lng, Math.min(30, Math.max(0.5, radiusKm)), when));
    }

    @GetMapping("/api/weather")
    public ResponseEntity<Map<String, Object>> weatherNear(@RequestHeader("Authorization") String authorization,
                                                           @RequestParam double lat, @RequestParam double lng) {
        authService.requireUser(authorization);
        WeatherService.Reading r = weather.near(lat, lng);
        if (r == null) return ResponseEntity.ok(Map.of("available", false));
        return ResponseEntity.ok(describe(r));
    }

    @GetMapping("/api/admin/live-data")
    public ResponseEntity<Map<String, Object>> overview(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("region", roads.regionName());
        r.put("bounds", roads.bounds());
        r.put("roads", roads.network().size());
        r.put("withPostedLimit", roads.network().withMaxspeed());
        r.put("oneWay", roads.network().oneways());
        r.put("restricted", roads.network().restricted());
        r.put("importing", roads.importing());
        r.put("status", status(roads.status()));
        r.put("law", "Limits not posted on the map follow MoRTH S.O. 1522(E), 6 Apr 2018 (5% tolerance)");
        out.put("roads", r);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("status", status(weather.status()));
        List<Map<String, Object>> towns = new ArrayList<>();
        for (WeatherService.Reading reading : weather.all()) towns.add(describe(reading));
        towns.sort(Comparator.comparing(m -> String.valueOf(m.get("place"))));
        w.put("towns", towns);
        out.put("weather", w);
        Map<String, Object> ml = new LinkedHashMap<>();
        ml.put("traffic", traffic.summary());
        ml.put("reportTrust", trust.summary());
        out.put("ml", ml);
        List<Map<String, Object>> jams = new ArrayList<>();
        for (TrafficSpeedModel.Jam j : traffic.jams()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", j.name());
            m.put("level", j.level().name());
            m.put("speedKmh", j.speedKmh());
            m.put("usualKmh", j.usualKmh());
            m.put("vehicles", j.vehicles());
            m.put("lat", j.lat());
            m.put("lng", j.lng());
            m.put("since", j.since().toString());
            jams.add(m);
        }
        out.put("jams", jams);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/admin/live-data/roads/refresh")
    public ResponseEntity<Map<String, Object>> refreshRoads(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        boolean started = roads.importInBackground();
        return ResponseEntity.ok(Map.of("started", started,
                "message", started ? "Downloading the region's roads from OpenStreetMap - this takes a few minutes."
                        : "A download is already running."));
    }

    @PostMapping("/api/admin/live-data/weather/refresh")
    public ResponseEntity<Map<String, Object>> refreshWeather(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        int n = weather.refresh(System.currentTimeMillis());
        return ResponseEntity.ok(Map.of("towns", n));
    }

    private static Map<String, Object> describe(WeatherService.Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("place", r.place().name());
        m.put("description", r.description());
        m.put("temperatureC", Double.isNaN(r.temperatureC()) ? null : Math.round(r.temperatureC() * 10) / 10.0);
        m.put("precipitationMm", r.precipitationMm());
        m.put("next3hPrecipitationMm", r.next3hPrecipitationMm());
        m.put("gustsKmh", r.gustsKmh());
        m.put("weatherCode", r.weatherCode());
        List<Map<String, String>> ws = new ArrayList<>();
        for (WeatherService.Warning w : r.warnings()) {
            ws.add(Map.of("kind", w.kind(), "level", w.level(), "title", w.title(), "message", w.message()));
        }
        m.put("warnings", ws);
        m.put("readAt", r.at().toString());
        m.put("source", "Open-Meteo");
        return m;
    }

    private static Map<String, Object> status(LiveDataStatus s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("state", "NOT_YET");
            return m;
        }
        m.put("state", s.getState());
        m.put("message", s.getMessage());
        m.put("lastSuccessAt", s.getLastSuccessAt() == null ? null : s.getLastSuccessAt().toString());
        m.put("lastAttemptAt", s.getLastAttemptAt() == null ? null : s.getLastAttemptAt().toString());
        m.put("items", s.getItems());
        m.put("origin", s.getOrigin());
        return m;
    }

    private void requireAdmin(String authorization) {
        User user = authService.requireUser(authorization);
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Only admins can see the live data panel");
    }
}
