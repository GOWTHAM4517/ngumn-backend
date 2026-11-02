package com.ngumn.backend.service;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.IncidentRepository;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.util.GeoUtil;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Accident black spots: places where crashes keep happening, worked out from
 * Raksio's own history - accident and fire help requests, crashes Raksio
 * detected, and accident reports the community didn't take down - over the
 * last 90 days.
 *
 * Points within 200 m of each other are one spot; a spot needs at least 2
 * separate accidents. Drivers heading towards one are warned (app
 * lib/safety-coach.tsx); the admin sees them in Analytics.
 *
 * Recomputed at most every 5 minutes - it's the same answer for everyone.
 */
@Service
public class HotspotService {

    static final Duration WINDOW = Duration.ofDays(90);
    static final double JOIN_M = 200;
    static final int MIN_COUNT = 2;
    static final long CACHE_MS = 5 * 60_000;

    private final IncidentRepository incidentRepository;
    private final RoadReportRepository roadReportRepository;

    private volatile List<Spot> cached = List.of();
    private volatile long cachedAt = 0;

    public HotspotService(IncidentRepository incidentRepository, RoadReportRepository roadReportRepository) {
        this.incidentRepository = incidentRepository;
        this.roadReportRepository = roadReportRepository;
    }

    /** One black spot. */
    public static final class Spot {
        double lat;
        double lng;
        int count;
        LocalDateTime last;
        final Set<String> kinds = new TreeSet<>();

        Map<String, Object> toMap(Double distance) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("latitude", lat);
            m.put("longitude", lng);
            m.put("accidents", count);
            m.put("lastAt", last);
            m.put("kinds", new ArrayList<>(kinds));
            if (distance != null) m.put("distanceMeters", Math.round(distance));
            return m;
        }
    }

    record Point(double lat, double lng, LocalDateTime at, String kind) {}

    /** Every black spot, most accidents first. */
    public List<Map<String, Object>> all(int limit) {
        return spots().stream().limit(limit).map(s -> s.toMap(null)).toList();
    }

    /** Black spots within radiusMeters of a point, nearest first. */
    public List<Map<String, Object>> near(double lat, double lng, double radiusMeters) {
        double r = Math.max(200, Math.min(radiusMeters, 50_000));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Spot s : spots()) {
            double d = GeoUtil.distanceMeters(lat, lng, s.lat, s.lng);
            if (d <= r) out.add(s.toMap(d));
        }
        out.sort(Comparator.comparingLong(m -> (Long) m.get("distanceMeters")));
        return out;
    }

    synchronized List<Spot> spots() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) return cached;
        cached = compute(points(LocalDateTime.now().minus(WINDOW)));
        cachedAt = now;
        return cached;
    }

    List<Point> points(LocalDateTime since) {
        List<Point> pts = new ArrayList<>();
        Set<String> cases = new HashSet<>();
        for (Incident i : incidentRepository.findByCreatedAtAfter(since)) {
            boolean crash = i.getType() == IncidentType.ACCIDENT || "CRASH".equals(i.getSource());
            boolean fire = i.getType() == IncidentType.FIRE;
            if (!crash && !fire) continue;
            if (i.getStatus() == IncidentStatus.FALSE_REPORT || i.getStatus() == IncidentStatus.CANCELLED) continue;
            // Police + ambulance for one accident are one accident.
            if (i.getCaseKey() != null && !cases.add(i.getCaseKey())) continue;
            pts.add(new Point(i.getLatitude(), i.getLongitude(), i.getCreatedAt(),
                    "CRASH".equals(i.getSource()) ? "Crash detected" : fire ? "Fire" : "Accident"));
        }
        for (RoadReport r : roadReportRepository.findByTypeAndTimestampAfter(ReportType.ACCIDENT, since)) {
            if (r.getStatus() == ReportStatus.REJECTED || r.getLatitude() == null || r.getLongitude() == null) continue;
            pts.add(new Point(r.getLatitude(), r.getLongitude(), r.getTimestamp(), "Accident reported"));
        }
        return pts;
    }

    /** Greedy clustering: each point joins the nearest spot within 200 m, else starts one. */
    static List<Spot> compute(List<Point> pts) {
        List<Spot> spots = new ArrayList<>();
        pts.sort(Comparator.comparing(Point::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (Point p : pts) {
            Spot best = null;
            double bestD = JOIN_M;
            for (Spot s : spots) {
                double d = GeoUtil.distanceMeters(p.lat(), p.lng(), s.lat, s.lng);
                if (d <= bestD) {
                    bestD = d;
                    best = s;
                }
            }
            if (best == null) {
                best = new Spot();
                best.lat = p.lat();
                best.lng = p.lng();
                spots.add(best);
            } else {
                best.lat = (best.lat * best.count + p.lat()) / (best.count + 1);
                best.lng = (best.lng * best.count + p.lng()) / (best.count + 1);
            }
            best.count++;
            best.kinds.add(p.kind());
            if (p.at() != null && (best.last == null || p.at().isAfter(best.last))) best.last = p.at();
        }
        return spots.stream().filter(s -> s.count >= MIN_COUNT)
                .sorted(Comparator.comparingInt((Spot s) -> s.count).reversed()).toList();
    }
}
