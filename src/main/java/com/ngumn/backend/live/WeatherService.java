package com.ngumn.backend.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.service.AlertService;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live weather for the towns of the Vijayawada region, from Open-Meteo (a
 * free weather API that needs no key; data from national weather services'
 * forecast models), read every 15 minutes.
 *
 * Warnings, for now and the next 3 hours:
 * - Thunderstorm: weather codes 95-99.
 * - Heavy rain: 7.6 mm or more in an hour (the meteorological "heavy rain"
 *   rate) - water-logging on underpasses and low roads.
 * - Rain: 2.5 mm/h or more - slippery roads (shown, not pushed).
 * - Fog: weather codes 45 / 48 - low visibility.
 * - Heat: 40 C or more (the heat-wave mark for the plains).
 * - Strong wind: gusts of 50 km/h or more.
 *
 * A new thunderstorm / heavy-rain / fog / heat / wind warning is pushed to
 * every Raksio user whose location (last 30 minutes) is within 15 km of
 * that town, at most once every 3 hours per warning and town.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    public static final String SOURCE = "WEATHER";
    public static final String HEADLINE = "Weather warning";
    private static final double NEAR_KM = 15;
    private static final long REPEAT_MS = 3 * 3_600_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The towns read. */
    public record Place(String name, double lat, double lng) {
    }

    public static final List<Place> PLACES = List.of(
            new Place("Vijayawada", 16.5062, 80.6480),
            new Place("Guntur", 16.3067, 80.4365),
            new Place("Gannavaram", 16.5413, 80.8017),
            new Place("Amaravati", 16.5131, 80.5165),
            new Place("Mangalagiri", 16.4307, 80.5525),
            new Place("Tenali", 16.2386, 80.6400));

    public record Warning(String kind, String level, String title, String message) {
    }

    public record Reading(Place place, double temperatureC, double precipitationMm, int weatherCode,
                          double gustsKmh, double next3hPrecipitationMm, int next3hWorstCode,
                          String description, List<Warning> warnings, LocalDateTime at) {
    }

    /** Answers an Open-Meteo URL with its JSON - the real call in production, a fixture in tests. */
    public interface Fetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    private final LiveDataStatusRepository statusRepository;
    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();
    private final Map<String, Long> pushed = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private Fetcher fetcher = this::get;

    @Value("${ngumn.weather.enabled:true}")
    private boolean enabled = true;

    public WeatherService(LiveDataStatusRepository statusRepository, VehicleRepository vehicleRepository,
                          AlertService alertService, NgumnWebSocketHandler webSocketHandler) {
        this.statusRepository = statusRepository;
        this.vehicleRepository = vehicleRepository;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
    }

    public void useFetcher(Fetcher f) {
        this.fetcher = f;
    }

    static String url() {
        StringJoiner lat = new StringJoiner(",");
        StringJoiner lng = new StringJoiner(",");
        for (Place p : PLACES) {
            lat.add(String.valueOf(p.lat()));
            lng.add(String.valueOf(p.lng()));
        }
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lng
                + "&current=temperature_2m,precipitation,weather_code,wind_gusts_10m"
                + "&hourly=precipitation,weather_code&forecast_hours=4&timezone=Asia%2FKolkata";
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Raksio-NGUMN/1.0").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Open-Meteo answered " + res.statusCode());
        return res.body();
    }

    @Scheduled(fixedDelay = 15 * 60_000L, initialDelay = 20_000)
    public void poll() {
        if (enabled) refresh(System.currentTimeMillis());
    }

    /** Reads every town now. Returns how many were read (0 when it failed). */
    public int refresh(long nowMs) {
        LiveDataStatus st = statusRepository.findById(SOURCE).orElseGet(() -> LiveDataStatus.builder().source(SOURCE).build());
        st.setLastAttemptAt(LocalDateTime.now());
        try {
            JsonNode root = JSON.readTree(fetcher.fetch(url()));
            List<JsonNode> list = new ArrayList<>();
            if (root.isArray()) root.forEach(list::add);
            else list.add(root);
            int n = 0;
            for (int i = 0; i < list.size() && i < PLACES.size(); i++) {
                Reading r = read(PLACES.get(i), list.get(i));
                Reading prev = readings.put(r.place().name(), r);
                n++;
                for (Warning w : r.warnings()) {
                    if (!"LOW".equals(w.level())) push(r, w, nowMs);
                }
                if (prev == null || !prev.warnings().equals(r.warnings())) {
                    webSocketHandler.broadcast("WEATHER", Map.of("place", r.place().name()));
                }
            }
            st.setState("OK");
            st.setLastSuccessAt(LocalDateTime.now());
            st.setItems((long) n);
            st.setOrigin("Open-Meteo (api.open-meteo.com)");
            Reading vja = readings.get("Vijayawada");
            st.setMessage(vja == null ? n + " towns read" : String.format(Locale.ROOT,
                    "%d towns read. Vijayawada now: %s, %.0f C, rain %.1f mm/h", n, vja.description(),
                    vja.temperatureC(), vja.precipitationMm()));
            statusRepository.save(st);
            return n;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Weather read failed: {}", e.getMessage());
            st.setState("FAILED");
            st.setMessage("Weather could not be read: " + e.getMessage());
            try {
                statusRepository.save(st);
            } catch (RuntimeException ignored) {
                // status is best-effort
            }
            return 0;
        }
    }

    static Reading read(Place p, JsonNode n) {
        JsonNode cur = n.path("current");
        double temp = cur.path("temperature_2m").asDouble(Double.NaN);
        double rain = cur.path("precipitation").asDouble(0);
        int code = cur.path("weather_code").asInt(0);
        double gusts = cur.path("wind_gusts_10m").asDouble(0);
        double next = 0;
        int worst = code;
        JsonNode hp = n.path("hourly").path("precipitation");
        JsonNode hc = n.path("hourly").path("weather_code");
        for (int i = 0; i < hp.size() && i < 4; i++) next = Math.max(next, hp.get(i).asDouble(0));
        for (int i = 0; i < hc.size() && i < 4; i++) {
            int c = hc.get(i).asInt(0);
            if (severity(c) > severity(worst)) worst = c;
        }
        List<Warning> w = warnings(p.name(), temp, rain, code, gusts, next, worst);
        return new Reading(p, temp, rain, code, gusts, next, worst, describe(code), w, LocalDateTime.now());
    }

    /** Ranks weather codes by danger, so the worst of the next hours is found. */
    static int severity(int code) {
        if (code >= 95) return 5;
        if (code == 65 || code == 82 || code == 67) return 4;
        if (code == 45 || code == 48) return 3;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return 2;
        if (code >= 51) return 1;
        return 0;
    }

    static List<Warning> warnings(String place, double temp, double rainNow, int codeNow, double gusts,
                                  double rainNext, int worstCode) {
        List<Warning> out = new ArrayList<>();
        if (codeNow >= 95 || worstCode >= 95) {
            out.add(new Warning("THUNDERSTORM", "HIGH", "Thunderstorm",
                    place + ": thunderstorm " + (codeNow >= 95 ? "now" : "expected in the next 3 hours")
                            + " - lightning and heavy rain. Don't stop under trees or hoardings; two-wheelers, find shelter."));
        }
        double rain = Math.max(rainNow, rainNext);
        if (rain >= 7.6) {
            out.add(new Warning("HEAVY_RAIN", "HIGH", "Heavy rain",
                    String.format(Locale.ROOT, "%s: heavy rain (%.0f mm/h) %s - underpasses and low roads may flood. "
                            + "Drive slowly with headlights on.", place, rain, rainNow >= 7.6 ? "now" : "in the next 3 hours")));
        } else if (rain >= 2.5) {
            out.add(new Warning("RAIN", "LOW", "Rain", place + ": rain - roads are slippery. Keep a longer gap and brake early."));
        }
        if (codeNow == 45 || codeNow == 48) {
            out.add(new Warning("FOG", "MEDIUM", "Fog",
                    place + ": fog - low visibility. Use low beam and fog lights, slow down, don't overtake."));
        }
        if (!Double.isNaN(temp) && temp >= 40) {
            out.add(new Warning("HEAT", "MEDIUM", "Heat",
                    String.format(Locale.ROOT, "%s: %.0f C - heat-wave conditions. Carry water; avoid long rides between 12 and 3 pm.", place, temp)));
        }
        if (gusts >= 50) {
            out.add(new Warning("WIND", "MEDIUM", "Strong wind",
                    String.format(Locale.ROOT, "%s: gusts of %.0f km/h - hold the handlebar firmly, watch for falling branches.", place, gusts)));
        }
        return out;
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    /** Tells the people near that town once (every 3 hours per warning). */
    int push(Reading r, Warning w, long nowMs) {
        String key = r.place().name() + ":" + w.kind();
        Long last = pushed.get(key);
        if (last != null && nowMs - last < REPEAT_MS) return 0;
        pushed.put(key, nowMs);
        LocalDateTime fresh = LocalDateTime.now().minusMinutes(30);
        Set<Long> told = new HashSet<>();
        for (Vehicle v : vehicleRepository.findByStatus(VehicleStatus.ACTIVE)) {
            User u = v.getOwner();
            if (u == null || v.getCurrentLatitude() == null || Boolean.TRUE.equals(v.getIsSimulated())) continue;
            if (v.getLastLocationUpdate() == null || v.getLastLocationUpdate().isBefore(fresh)) continue;
            if (!nearest(v.getCurrentLatitude(), v.getCurrentLongitude()).name().equals(r.place().name())) continue;
            if (GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), r.place().lat(), r.place().lng()) > NEAR_KM * 1000) continue;
            if (!told.add(u.getId())) continue;
            alertService.raise(v, u, AlertType.ROAD_HAZARD, "HIGH".equals(w.level()) ? RiskLevel.HIGH : RiskLevel.MEDIUM,
                    HEADLINE + ": " + w.message(), r.place().lat(), r.place().lng());
        }
        return told.size();
    }

    public static Place nearest(double lat, double lng) {
        Place best = PLACES.get(0);
        double bestD = Double.MAX_VALUE;
        for (Place p : PLACES) {
            double d = GeoUtil.distanceMeters(lat, lng, p.lat(), p.lng());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** The reading of the town nearest to a point (null before the first read). */
    public Reading near(double lat, double lng) {
        return readings.get(nearest(lat, lng).name());
    }

    public Collection<Reading> all() {
        return readings.values();
    }

    public LiveDataStatus status() {
        return statusRepository.findById(SOURCE).orElse(null);
    }
}
