package com.ngumn.backend.ml;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.roads.RoadDataService;
import com.ngumn.backend.roads.RoadInfo;
import com.ngumn.backend.roads.RoadNetwork;
import com.ngumn.backend.roads.RoadRules;
import com.ngumn.backend.roads.RoadSegment;
import com.ngumn.backend.roads.Polyline;
import com.ngumn.backend.service.AlertService;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Machine learning: the normal speed of every road at every hour, learned
 * from real trips - and from it, traffic jams detected live and predicted
 * ahead of time.
 *
 * Learning. Every location update of a vehicle on a mapped road (RoadRules)
 * is a speed reading for that road, in that direction, at that hour of the
 * week (168 hours). The model keeps, for each, a running mean and variance
 * (Welford's online algorithm), plus one for all hours together. Readings
 * from the same vehicle on the same road are taken at most every 20 s, and
 * a standing vehicle only counts if it was moving in the last 3 minutes
 * (stuck in traffic, not parked). After 2,000 readings older ones fade, so
 * the model follows a road that changes (a new flyover, a diversion).
 *
 * Before a road has readings, the model starts from a prior: a typical
 * city speed for that class of road (highway 50, main road 35, link road
 * 28, local road 22 km/h), worth 5 readings. So it is useful at once and
 * becomes data-driven road by road.
 *
 * Expected speed for a road at an hour = the hour's learned mean, shrunk
 * towards the road's all-hours mean, itself shrunk towards the prior
 * (empirical Bayes) - so few readings never give wild answers. Free-flow speed =
 * the all-hours mean plus one standard deviation (about the 85th
 * percentile, the usual traffic-engineering measure).
 *
 * Live jams. Every minute, each road with readings in the last 5 minutes is
 * judged on each vehicle's latest reading: median speed of at least 2 vehicles (or one vehicle seen over 90
 * seconds) below 35% of free flow and under 15 km/h = JAM; below 60% and
 * under 25 km/h = SLOW. A new jam is pushed to every map and drivers 150 m
 * - 2 km away heading towards it are warned ("Traffic jam ahead").
 *
 * Prediction. For any hour, roads whose expected speed is below 60% of free
 * flow (with 5+ readings for that hour) are "usually slow at this time".
 *
 * What it learned is saved every 5 minutes and when the server stops.
 *
 * Accuracy is measured honestly: each new reading is first predicted, then
 * learned from; the running mean absolute error of the model is shown next
 * to that of the fixed prior (Control Centre > Live data & AI).
 */
@Service
public class TrafficSpeedModel {

    private static final Logger log = LoggerFactory.getLogger(TrafficSpeedModel.class);
    public static final String JAM_HEADLINE = "Traffic jam ahead";

    static final long SAMPLE_GAP_MS = 20_000;
    static final long LIVE_WINDOW_MS = 5 * 60_000;
    static final long STUCK_GRACE_MS = 3 * 60_000;
    static final int PRIOR_WEIGHT = 5;
    static final long FADE_AFTER = 2000;
    static final long ALERT_REPEAT_MS = 20 * 60_000;

    public enum Level { FREE, SLOW, JAM }

    /** Running statistics for one key. */
    static final class Stat {
        long n;
        double mean;
        double m2;
        boolean dirty;

        void add(double x) {
            n = Math.min(n + 1, FADE_AFTER);
            double d = x - mean;
            mean += d / n;
            m2 += d * (x - mean);
            if (n == FADE_AFTER) m2 *= (FADE_AFTER - 1.0) / FADE_AFTER; // keep the variance from growing without end
            dirty = true;
        }

        double sd() {
            return n > 1 ? Math.sqrt(Math.max(0, m2 / (n - 1))) : 0;
        }
    }

    record Obs(long at, long vehicleId, double kmh, double lat, double lng) {
    }

    /** A jam the model sees right now. */
    public record Jam(long roadId, int direction, String name, Level level, double speedKmh, double usualKmh,
                      int vehicles, double lat, double lng, LocalDateTime since) {
    }

    private final RoadDataService roads;
    private final TrafficSpeedStatRepository repository;
    private final VehicleRepository vehicleRepository;
    private final AlertService alertService;
    private final NgumnWebSocketHandler webSocketHandler;

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();
    private final Map<String, Deque<Obs>> live = new ConcurrentHashMap<>();
    private final Map<Long, Object[]> lastSample = new ConcurrentHashMap<>(); // vehicleId -> {roadKey, time}
    private final Map<Long, Long> lastMoving = new ConcurrentHashMap<>();
    private final Map<String, Jam> jams = new ConcurrentHashMap<>();
    private final Map<String, Long> warned = new ConcurrentHashMap<>();

    private long evaluated;
    private double absErrModel;
    private double absErrPrior;
    private long totalSamples;

    public TrafficSpeedModel(RoadDataService roads, TrafficSpeedStatRepository repository,
                             VehicleRepository vehicleRepository, AlertService alertService,
                             NgumnWebSocketHandler webSocketHandler) {
        this.roads = roads;
        this.repository = repository;
        this.vehicleRepository = vehicleRepository;
        this.alertService = alertService;
        this.webSocketHandler = webSocketHandler;
    }

    // ------------------------------------------------------------------ keys and time

    static String roadKey(long roadId, int dir) {
        return roadId + ":" + dir;
    }

    static String statKey(String roadKey, int hourOfWeek) {
        return roadKey + ":" + hourOfWeek;
    }

    /** 0 = Monday 00:00-01:00 ... 167 = Sunday 23:00-24:00. */
    public static int hourOfWeek(LocalDateTime t) {
        return (t.getDayOfWeek().getValue() - 1) * 24 + t.getHour();
    }

    static int direction(RoadSegment s, boolean forward) {
        int ow = s.getOneway() == null ? 0 : s.getOneway();
        if (ow != 0) return 1; // a one-way road has one direction
        return forward ? 1 : -1;
    }

    /** A typical city speed for a class of road - the model's starting point (km/h). */
    static double prior(RoadSegment s) {
        String h = s.getHighway() == null ? "" : s.getHighway();
        double v;
        if (h.equals("motorway")) v = 70;
        else if (h.startsWith("trunk")) v = 50;
        else if (h.startsWith("primary") || h.startsWith("secondary")) v = 35;
        else if (h.startsWith("tertiary") || h.endsWith("_link")) v = 28;
        else v = 22;
        if (s.getMaxspeedKmh() != null) v = Math.min(v, s.getMaxspeedKmh());
        return v;
    }

    // ------------------------------------------------------------------ learning

    /** A location update on a mapped road: learn from its speed. Returns true if it was used. */
    public boolean observe(Vehicle v, RoadInfo road, double speedKmh, LocalDateTime at, long nowMs) {
        if (v == null || road == null || !road.isMapped() || road.getRoadId() == null) return false;
        if (Boolean.TRUE.equals(v.getIsSimulated())) return false;
        TravelMode mode = v.effectiveTravelMode();
        if (!mode.motorised() || (mode == TravelMode.EMERGENCY && Boolean.TRUE.equals(v.getEmergencyStatus()))) return false;
        RoadNetwork.Road r = roads.network().road(road.getRoadId());
        if (r == null) return false;
        long vid = v.getId();
        if (speedKmh >= 10) lastMoving.put(vid, nowMs);
        if (speedKmh < 3) {
            Long moving = lastMoving.get(vid);
            if (moving == null || nowMs - moving > STUCK_GRACE_MS) return false; // parked, not in traffic
        }
        int dir = direction(r.row(), road.isForward());
        String key = roadKey(road.getRoadId(), dir);
        Object[] last = lastSample.get(vid);
        if (last != null && key.equals(last[0]) && nowMs - (Long) last[1] < SAMPLE_GAP_MS) return false;
        lastSample.put(vid, new Object[]{key, nowMs});

        int how = hourOfWeek(at);
        double prior = prior(r.row());
        synchronized (this) {
            double predicted = expected(key, how, prior);
            evaluated++;
            absErrModel += Math.abs(predicted - speedKmh);
            absErrPrior += Math.abs(prior - speedKmh);
            totalSamples++;
        }
        stats.computeIfAbsent(statKey(key, how), k -> new Stat()).add(speedKmh);
        stats.computeIfAbsent(statKey(key, -1), k -> new Stat()).add(speedKmh);
        Deque<Obs> q = live.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Obs(nowMs, vid, speedKmh, v.getCurrentLatitude() != null ? v.getCurrentLatitude() : 0,
                    v.getCurrentLongitude() != null ? v.getCurrentLongitude() : 0));
            while (!q.isEmpty() && nowMs - q.peekFirst().at() > LIVE_WINDOW_MS) q.pollFirst();
        }
        return true;
    }

    /**
     * Expected speed on a road (key) at an hour of the week, by shrinkage
     * (empirical Bayes): the road's all-hours mean is pulled towards the prior
     * while it has few readings, and the hour's mean towards that while the
     * hour has few. With plenty of readings for the hour, the hour decides.
     */
    double expected(String key, int hourOfWeek, double prior) {
        Stat h = stats.get(statKey(key, hourOfWeek));
        Stat a = stats.get(statKey(key, -1));
        double base = a == null ? prior : (a.mean * a.n + prior * PRIOR_WEIGHT) / (a.n + PRIOR_WEIGHT);
        if (h == null) return base;
        return (h.mean * h.n + base * PRIOR_WEIGHT) / (h.n + PRIOR_WEIGHT);
    }

    /** Free-flow speed: about the 85th percentile of everything seen on that road (mean + 1 sd), or the prior. */
    double freeFlow(String key, double prior) {
        Stat a = stats.get(statKey(key, -1));
        if (a == null || a.n < 20) return prior * 1.2;
        return Math.max(10, Math.min(120, a.mean + a.sd()));
    }

    // ------------------------------------------------------------------ live jams

    /** The level of a road right now from its readings in the last 5 minutes (null if too few). */
    Jam judge(String key, long nowMs) {
        Deque<Obs> q = live.get(key);
        if (q == null) return null;
        List<Obs> recent;
        synchronized (q) {
            while (!q.isEmpty() && nowMs - q.peekFirst().at() > LIVE_WINDOW_MS) q.pollFirst();
            recent = new ArrayList<>(q);
        }
        if (recent.isEmpty()) return null;
        // Each vehicle's latest reading: what the road is like now, not 4 minutes ago.
        Map<Long, Obs> latest = new LinkedHashMap<>();
        for (Obs o : recent) latest.put(o.vehicleId(), o);
        long span = recent.get(recent.size() - 1).at() - recent.get(0).at();
        boolean enough = latest.size() >= 2 || (recent.size() >= 4 && span >= 90_000);
        if (!enough) return null;
        List<Obs> now = latest.size() >= 2 ? new ArrayList<>(latest.values())
                : recent.subList(Math.max(0, recent.size() - 3), recent.size());
        double[] speeds = new double[now.size()];
        double lat = 0, lng = 0;
        for (int i = 0; i < now.size(); i++) {
            speeds[i] = now.get(i).kmh();
            lat += now.get(i).lat();
            lng += now.get(i).lng();
        }
        Set<Long> vehicles = latest.keySet();
        Arrays.sort(speeds);
        double median = speeds[speeds.length / 2];
        String[] parts = key.split(":");
        long roadId = Long.parseLong(parts[0]);
        int dir = Integer.parseInt(parts[1]);
        RoadNetwork.Road r = roads.network().road(roadId);
        if (r == null) return null;
        double ff = freeFlow(key, prior(r.row()));
        Level level = median <= Math.max(5, 0.35 * ff) && median < 15 ? Level.JAM
                : median <= 0.6 * ff && median < 25 ? Level.SLOW : Level.FREE;
        Jam prev = jams.get(key);
        LocalDateTime since = prev != null && prev.level() == level ? prev.since() : LocalDateTime.now();
        return new Jam(roadId, dir, RoadRules.displayName(r.row()), level, round1(median), round1(ff), vehicles.size(),
                lat / now.size(), lng / now.size(), since);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void detect() {
        detect(System.currentTimeMillis());
    }

    /** Judges every road with recent readings; tells people about new jams. Returns the jams now. */
    public List<Jam> detect(long nowMs) {
        boolean changed = false;
        for (String key : new ArrayList<>(live.keySet())) {
            Jam j = judge(key, nowMs);
            Jam prev = jams.get(key);
            if (j == null || j.level() == Level.FREE) {
                if (prev != null) {
                    jams.remove(key);
                    changed = true;
                }
                Deque<Obs> q = live.get(key);
                if (q != null && q.isEmpty()) live.remove(key);
                continue;
            }
            jams.put(key, j);
            if (prev == null || prev.level() != j.level()) changed = true;
            if (j.level() == Level.JAM && (prev == null || prev.level() != Level.JAM)) warnApproaching(key, j, nowMs);
        }
        if (changed && webSocketHandler != null) {
            webSocketHandler.broadcast("TRAFFIC", Map.of("jams", jams.size()));
        }
        return new ArrayList<>(jams.values());
    }

    /** Drivers 150 m - 2 km away, moving towards the jam, get one warning (at most every 20 minutes per jam). */
    int warnApproaching(String key, Jam j, long nowMs) {
        int told = 0;
        LocalDateTime fresh = LocalDateTime.now().minusMinutes(3);
        Deque<Obs> q = live.get(key);
        Set<Long> inJam = new HashSet<>();
        if (q != null) synchronized (q) {
            for (Obs o : q) inJam.add(o.vehicleId());
        }
        for (Vehicle v : vehicleRepository.findByStatus(VehicleStatus.ACTIVE)) {
            if (v.getOwner() == null || v.getCurrentLatitude() == null || inJam.contains(v.getId())) continue;
            if (v.getLastLocationUpdate() == null || v.getLastLocationUpdate().isBefore(fresh)) continue;
            if (!v.effectiveTravelMode().motorised() || Boolean.TRUE.equals(v.getIsSimulated())) continue;
            double speed = v.getSpeedKmh() == null ? 0 : v.getSpeedKmh();
            if (speed < 10) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), j.lat(), j.lng());
            if (d < 150 || d > 2000) continue;
            double toward = GeoUtil.bearingDegrees(v.getCurrentLatitude(), v.getCurrentLongitude(), j.lat(), j.lng());
            if (v.getDirectionDegrees() == null || GeoUtil.angleDifference(v.getDirectionDegrees(), toward) > 40) continue;
            String wk = v.getOwner().getId() + ":" + key;
            Long last = warned.get(wk);
            if (last != null && nowMs - last < ALERT_REPEAT_MS) continue;
            warned.put(wk, nowMs);
            String dist = d >= 1000 ? String.format(Locale.ROOT, "%.1f km", d / 1000) : Math.round(d / 50) * 50 + " m";
            String msg = String.format(Locale.ROOT,
                    "%s: %s, %s ahead - vehicles moving at %d km/h (usually %d). Consider another route.",
                    JAM_HEADLINE, j.name(), dist, Math.round(j.speedKmh()), Math.round(j.usualKmh()));
            if (alertService != null) {
                alertService.raise(v, v.getOwner(), AlertType.NEARBY_HAZARD, RiskLevel.MEDIUM, msg, j.lat(), j.lng());
            }
            told++;
        }
        return told;
    }

    // ------------------------------------------------------------------ for the app and the map

    /** Jams and slow roads within radiusKm of a point, with their shapes for the map. */
    public List<Map<String, Object>> liveNear(double lat, double lng, double radiusKm) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Jam j : jams.values()) {
            if (GeoUtil.distanceMeters(lat, lng, j.lat(), j.lng()) > radiusKm * 1000) continue;
            out.add(describe(j.roadId(), j.name(), j.level().name(), j.speedKmh(), j.usualKmh(), j.vehicles(), j.since()));
        }
        return out;
    }

    /** Roads usually slow at a given time (learned), within radiusKm of a point. */
    public List<Map<String, Object>> forecastNear(double lat, double lng, double radiusKm, LocalDateTime at) {
        int how = hourOfWeek(at);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 3 || Integer.parseInt(parts[2]) != how || e.getValue().n < 5) continue;
            String key = parts[0] + ":" + parts[1];
            if (!done.add(key)) continue;
            RoadNetwork.Road r = roads.network().road(Long.parseLong(parts[0]));
            if (r == null) continue;
            double mid = GeoUtil.distanceMeters(lat, lng, r.lats()[r.lats().length / 2], r.lngs()[r.lngs().length / 2]);
            if (mid > radiusKm * 1000) continue;
            double prior = prior(r.row());
            double exp = expected(key, how, prior);
            double ff = freeFlow(key, prior);
            if (exp > 0.6 * ff) continue;
            Map<String, Object> m = describe(r.row().getId(), RoadRules.displayName(r.row()),
                    exp <= 0.35 * ff ? "JAM" : "SLOW", round1(exp), round1(ff), 0, null);
            m.put("samples", e.getValue().n);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> describe(long roadId, String name, String level, double speed, double usual,
                                         int vehicles, LocalDateTime since) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roadId", roadId);
        m.put("name", name);
        m.put("level", level);
        m.put("speedKmh", speed);
        m.put("usualKmh", usual);
        if (vehicles > 0) m.put("vehicles", vehicles);
        if (since != null) m.put("since", since.toString());
        RoadNetwork.Road r = roads.network().road(roadId);
        if (r != null) m.put("polyline", Polyline.encode(r.lats(), r.lngs()));
        return m;
    }

    public List<Jam> jams() {
        return new ArrayList<>(jams.values());
    }

    public synchronized Map<String, Object> summary() {
        Set<String> roadsLearned = new HashSet<>();
        for (String k : stats.keySet()) roadsLearned.add(k.substring(0, k.indexOf(':')));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Road speed by hour of the week (online statistical learning)");
        m.put("samples", totalSamples);
        m.put("roadsLearned", roadsLearned.size());
        m.put("liveJams", jams.values().stream().filter(j -> j.level() == Level.JAM).count());
        m.put("liveSlow", jams.values().stream().filter(j -> j.level() == Level.SLOW).count());
        if (evaluated > 0) {
            double mae = absErrModel / evaluated;
            double base = absErrPrior / evaluated;
            m.put("modelErrorKmh", round1(mae));
            m.put("fixedRuleErrorKmh", round1(base));
            m.put("improvementPercent", base > 0 ? round1(100 * (base - mae) / base) : null);
        }
        m.put("learnsFrom", "Every location update of a vehicle on a mapped road");
        return m;
    }

    // ------------------------------------------------------------------ saving

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        try {
            long n = 0;
            for (TrafficSpeedStat s : repository.findAll()) {
                Stat st = new Stat();
                st.n = s.getSamples() == null ? 0 : s.getSamples();
                st.mean = s.getMeanKmh() == null ? 0 : s.getMeanKmh();
                st.m2 = s.getM2() == null ? 0 : s.getM2();
                stats.put(s.getId(), st);
                if (s.getHourOfWeek() != null && s.getHourOfWeek() == -1) n += st.n;
            }
            synchronized (this) {
                totalSamples = n;
            }
            if (!stats.isEmpty()) log.info("Traffic model loaded: {} learned speed figures", stats.size());
        } catch (RuntimeException e) {
            log.warn("Could not load the traffic model: {}", e.getMessage());
        }
    }

    /** Render stops or sleeps the server: keep what was learned since the last save. */
    @jakarta.annotation.PreDestroy
    public void flushOnShutdown() {
        flush();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void flush() {
        List<TrafficSpeedStat> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            Stat s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            String[] p = e.getKey().split(":");
            rows.add(TrafficSpeedStat.builder().id(e.getKey()).roadId(Long.parseLong(p[0]))
                    .direction(Integer.parseInt(p[1])).hourOfWeek(Integer.parseInt(p[2]))
                    .samples(s.n).meanKmh(s.mean).m2(s.m2).updatedAt(now).build());
        }
        if (rows.isEmpty()) return;
        try {
            repository.saveAll(rows);
        } catch (RuntimeException e) {
            log.warn("Could not save the traffic model: {}", e.getMessage());
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
