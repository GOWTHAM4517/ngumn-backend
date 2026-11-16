package com.ngumn.backend.roads;

import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.live.LiveDataStatus;
import com.ngumn.backend.live.LiveDataStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real roads of the Vijayawada region - Vijayawada, Gannavaram,
 * Amaravati, Mangalagiri, Guntur, Tenali - downloaded from OpenStreetMap
 * through the public Overpass API and refreshed every week.
 *
 * What is downloaded: every main road (national / state highways, main and
 * link roads, with their ramps), plus any smaller road the map gives a speed
 * limit, a one-way direction or a vehicle ban (pedestrian streets,
 * "no motor vehicles", "no heavy vehicles", and timed bans). The region is fetched in 16 tiles, one after
 * another, from the first Overpass server that answers. If more than a
 * quarter of the tiles fail, the old roads are kept and the import is tried
 * again an hour later.
 *
 * At start-up the roads saved in the database are loaded into memory
 * (RoadNetwork). If there are none, or they are more than 7 days old, a new
 * download starts in the background - the server is usable meanwhile.
 */
@Service
public class RoadDataService {

    private static final Logger log = LoggerFactory.getLogger(RoadDataService.class);
    public static final String SOURCE = "ROADS";
    private static final int TILES = 4; // 4 x 4
    private static final int SAVE_CHUNK = 500;

    /** Answers an Overpass query with its JSON - the real HTTP call in production, a fixture in tests. */
    public interface Fetcher {
        String fetch(String overpassQuery) throws IOException, InterruptedException;
    }

    private final RoadSegmentRepository repository;
    private final LiveDataStatusRepository statusRepository;
    /** For fast multi-row inserts on the real database (absent in tests, which use the repository). */
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile RoadNetwork network = RoadNetwork.EMPTY;
    private volatile String lastOrigin;
    private Fetcher fetcher;

    @Value("${ngumn.region.name:Vijayawada region}")
    private String regionName = "Vijayawada region";
    @Value("${ngumn.region.south:16.18}")
    private double south = 16.18;
    @Value("${ngumn.region.west:80.38}")
    private double west = 80.38;
    @Value("${ngumn.region.north:16.72}")
    private double north = 16.72;
    @Value("${ngumn.region.east:80.95}")
    private double east = 80.95;
    @Value("${ngumn.roads.overpass-urls:https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter,https://overpass.private.coffee/api/interpreter}")
    private String overpassUrls = "https://overpass-api.de/api/interpreter";
    @Value("${ngumn.roads.enabled:true}")
    private boolean enabled = true;
    @Value("${ngumn.public-url:}")
    private String publicUrl = "";

    public RoadDataService(RoadSegmentRepository repository, LiveDataStatusRepository statusRepository) {
        this.repository = repository;
        this.statusRepository = statusRepository;
        this.fetcher = this::overpass;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** For tests: answer queries from a fixture instead of the internet. */
    public void useFetcher(Fetcher f) {
        this.fetcher = f;
    }

    public RoadNetwork network() {
        return network;
    }

    public boolean loaded() {
        return network.size() > 0;
    }

    /** The road and its rules where a vehicle is. */
    public RoadInfo roadAt(double lat, double lng, Double heading, double speedKmh, Double accuracy, TravelMode mode) {
        return RoadRules.at(network, lat, lng, heading, speedKmh, accuracy, mode);
    }

    public boolean inRegion(double lat, double lng) {
        return lat >= south && lat <= north && lng >= west && lng <= east;
    }

    public String regionName() {
        return regionName;
    }

    public double[] bounds() {
        return new double[]{south, west, north, east};
    }

    // ------------------------------------------------------------------ start-up and schedule

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        Thread t = new Thread(() -> {
            try {
                loadFromDatabase();
                LiveDataStatus st = statusRepository.findById(SOURCE).orElse(null);
                boolean stale = st == null || st.getLastSuccessAt() == null
                        || st.getLastSuccessAt().isBefore(LocalDateTime.now().minusDays(7));
                if (enabled && (network.size() == 0 || stale)) importNow();
            } catch (RuntimeException e) {
                log.warn("Road data start-up failed: {}", e.getMessage());
            }
        }, "road-data-start");
        t.setDaemon(true);
        t.start();
    }

    /** Every Sunday 03:30 India time - roads change slowly, and the servers are quiet then. */
    @Scheduled(cron = "0 30 3 * * SUN", zone = "Asia/Kolkata")
    public void weekly() {
        if (enabled) importNow();
    }

    /** Retry hourly after a failed import. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void retryIfFailed() {
        if (!enabled) return;
        LiveDataStatus st = statusRepository.findById(SOURCE).orElse(null);
        if (st != null && "FAILED".equals(st.getState())) importNow();
    }

    public void loadFromDatabase() {
        List<RoadSegment> rows = repository.findAll();
        if (!rows.isEmpty()) {
            network = new RoadNetwork(rows);
            log.info("Loaded {} roads of the {} ({} with posted limits, {} one-way)",
                    network.size(), regionName, network.withMaxspeed(), network.oneways());
        }
    }

    /** Starts a download in the background unless one is running. Returns false if one already is. */
    public boolean importInBackground() {
        if (running.get()) return false;
        Thread t = new Thread(this::importNow, "road-data-import");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public boolean importing() {
        return running.get();
    }

    /** Downloads the region now (blocking). Returns the number of roads, or -1 if it failed. */
    public int importNow() {
        if (!running.compareAndSet(false, true)) return -1;
        LocalDateTime started = LocalDateTime.now();
        status("RUNNING", "Downloading the roads of the " + regionName + " from OpenStreetMap...", null, false);
        try {
            Map<Long, RoadSegment> roads = new LinkedHashMap<>();
            int failed = 0;
            String lastError = null;
            double dLat = (north - south) / TILES;
            double dLng = (east - west) / TILES;
            for (int r = 0; r < TILES; r++) {
                for (int c = 0; c < TILES; c++) {
                    double s = south + r * dLat, w = west + c * dLng;
                    String q = query(s, w, s + dLat, w + dLng);
                    try {
                        for (RoadSegment seg : OsmRoadParser.parse(fetcher.fetch(q), started)) {
                            roads.putIfAbsent(seg.getId(), seg);
                        }
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        lastError = e.getMessage();
                        log.warn("Road tile {},{} failed: {}", r, c, e.getMessage());
                    }
                }
            }
            if (failed > TILES * TILES / 4 || roads.isEmpty()) {
                status("FAILED", failed + " of " + (TILES * TILES) + " map tiles could not be downloaded"
                        + (lastError != null ? " (" + lastError + ")" : "") + ". The previous roads are still used.", null, false);
                return -1;
            }
            RoadNetwork fresh = new RoadNetwork(roads.values());
            network = fresh; // used at once, before saving
            save(new ArrayList<>(roads.values()));
            String msg = String.format(Locale.ROOT, "%,d roads of the %s: %,d with a posted speed limit, %,d one-way, %,d closed to some vehicles%s",
                    fresh.size(), regionName, fresh.withMaxspeed(), fresh.oneways(), fresh.restricted(),
                    failed > 0 ? " (" + failed + " tiles missing, retried next week)" : "");
            status("OK", msg, (long) fresh.size(), true);
            log.info(msg);
            return fresh.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status("FAILED", "Interrupted", null, false);
            return -1;
        } catch (RuntimeException e) {
            log.warn("Road import failed", e);
            status("FAILED", "Import failed: " + e.getMessage(), null, false);
            return -1;
        } finally {
            running.set(false);
        }
    }

    private static final String[] COLUMNS = {"id", "name", "ref", "highway", "maxspeed_kmh", "maxspeed_forward_kmh",
            "maxspeed_backward_kmh", "oneway", "cycles_both_ways", "divided", "lanes", "no_motor_vehicles", "no_motor_hours",
            "no_heavy_vehicles", "no_heavy_hours", "geometry", "min_lat", "min_lng", "max_lat", "max_lng", "imported_at"};
    /** Rows per INSERT statement: a remote database is reached once per 200 roads, not once per road. */
    private static final int ROWS_PER_INSERT = 200;

    private void save(List<RoadSegment> rows) {
        if (jdbc == null) {
            repository.deleteAllInBatch();
            for (int i = 0; i < rows.size(); i += SAVE_CHUNK) {
                repository.saveAll(rows.subList(i, Math.min(rows.size(), i + SAVE_CHUNK)));
            }
            return;
        }
        jdbc.update("DELETE FROM road_segments");
        String cols = String.join(",", COLUMNS);
        String one = "(" + String.join(",", java.util.Collections.nCopies(COLUMNS.length, "?")) + ")";
        for (int i = 0; i < rows.size(); i += ROWS_PER_INSERT) {
            List<RoadSegment> part = rows.subList(i, Math.min(rows.size(), i + ROWS_PER_INSERT));
            String sql = "INSERT INTO road_segments (" + cols + ") VALUES "
                    + String.join(",", java.util.Collections.nCopies(part.size(), one));
            List<Object> args = new ArrayList<>(part.size() * COLUMNS.length);
            for (RoadSegment r : part) {
                args.add(r.getId());
                args.add(r.getName());
                args.add(r.getRef());
                args.add(r.getHighway());
                args.add(r.getMaxspeedKmh());
                args.add(r.getMaxspeedForwardKmh());
                args.add(r.getMaxspeedBackwardKmh());
                args.add(r.getOneway() == null ? 0 : r.getOneway());
                args.add(Boolean.TRUE.equals(r.getCyclesBothWays()));
                args.add(Boolean.TRUE.equals(r.getDivided()));
                args.add(r.getLanes());
                args.add(Boolean.TRUE.equals(r.getNoMotorVehicles()));
                args.add(r.getNoMotorHours());
                args.add(Boolean.TRUE.equals(r.getNoHeavyVehicles()));
                args.add(r.getNoHeavyHours());
                args.add(r.getGeometry());
                args.add(r.getMinLat());
                args.add(r.getMinLng());
                args.add(r.getMaxLat());
                args.add(r.getMaxLng());
                // Bound as a Timestamp, exactly as Hibernate binds a LocalDateTime, so both write the same time.
                args.add(r.getImportedAt() == null ? null : java.sql.Timestamp.valueOf(r.getImportedAt()));
            }
            jdbc.update(sql, args.toArray());
        }
    }

    private void status(String state, String message, Long items, boolean success) {
        try {
            LiveDataStatus st = statusRepository.findById(SOURCE).orElseGet(() -> LiveDataStatus.builder().source(SOURCE).build());
            st.setState(state);
            st.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
            st.setLastAttemptAt(LocalDateTime.now());
            if (success) {
                st.setLastSuccessAt(LocalDateTime.now());
                st.setItems(items);
                st.setOrigin(lastOrigin != null ? "OpenStreetMap via " + lastOrigin : "OpenStreetMap");
            }
            statusRepository.save(st);
        } catch (RuntimeException e) {
            log.warn("Could not save road data status: {}", e.getMessage());
        }
    }

    public LiveDataStatus status() {
        return statusRepository.findById(SOURCE).orElse(null);
    }

    // ------------------------------------------------------------------ Overpass

    /** The Overpass QL query for one tile (south, west, north, east). */
    static String query(double s, double w, double n, double e) {
        String box = String.format(Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", s, w, n, e);
        return "[out:json][timeout:180];("
                + "way[\"highway\"~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link)$\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][\"maxspeed\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][\"oneway\"~\"^(yes|1|true|-1)$\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|road)$\"][\"junction\"=\"roundabout\"]" + box + ";"
                + "way[\"highway\"=\"pedestrian\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][\"motor_vehicle\"=\"no\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][\"access\"=\"no\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][\"hgv\"=\"no\"]" + box + ";"
                + "way[\"highway\"~\"^(unclassified|residential|living_street|service|road)$\"][~\"^(hgv|motor_vehicle|access):conditional$\"~\".\"]" + box + ";"
                + ");out tags geom;";
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Asks each Overpass server in turn; the first good answer wins. */
    private String overpass(String query) throws IOException, InterruptedException {
        IOException last = null;
        String ua = "Raksio-NGUMN/1.0 (road-safety app for the Vijayawada region; "
                + (publicUrl == null || publicUrl.isBlank() ? "https://ngumn-backend.onrender.com" : publicUrl) + ")";
        for (String url : overpassUrls.split(",")) {
            url = url.trim();
            if (url.isEmpty()) continue;
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(200))
                        .header("User-Agent", ua)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                        .build();
                try {
                    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200 && res.body().startsWith("{")) {
                        lastOrigin = URI.create(url).getHost();
                        Thread.sleep(1500); // be gentle with a free public service
                        return res.body();
                    }
                    last = new IOException(URI.create(url).getHost() + " answered " + res.statusCode());
                    if (res.statusCode() == 429 || res.statusCode() == 504) Thread.sleep(15_000);
                } catch (IOException e) {
                    last = new IOException(URI.create(url).getHost() + ": " + e.getMessage(), e);
                }
            }
        }
        throw last != null ? last : new IOException("No Overpass server configured");
    }
}
