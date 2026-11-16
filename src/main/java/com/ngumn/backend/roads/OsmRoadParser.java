package com.ngumn.backend.roads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an Overpass API answer ("[out:json] ... out tags geom;") into
 * RoadSegment rows. Only roads that motor vehicles use are kept; footpaths,
 * tracks and cycleways never reach here (the query doesn't ask for them).
 */
public final class OsmRoadParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Roads whose class alone matters for the law (divided highways) or that carry names drivers know. */
    public static final Set<String> MAIN_ROADS = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link");

    /** Smaller roads, kept only when the map gives them a speed limit, a one-way direction or a vehicle ban. */
    public static final Set<String> MINOR_ROADS = Set.of(
            "unclassified", "residential", "living_street", "service", "road");

    /** Values that let motor vehicles in (anything else in motor_vehicle / access bans them). */
    private static final Set<String> ALLOWED = Set.of("yes", "designated", "permissive", "destination", "delivery",
            "customers", "official", "agricultural", "forestry", "discouraged");

    private OsmRoadParser() {
    }

    public static List<RoadSegment> parse(InputStream in, LocalDateTime importedAt) throws IOException {
        return parse(JSON.readTree(in), importedAt);
    }

    public static List<RoadSegment> parse(String json, LocalDateTime importedAt) throws IOException {
        return parse(JSON.readTree(json), importedAt);
    }

    static List<RoadSegment> parse(JsonNode root, LocalDateTime importedAt) {
        List<RoadSegment> out = new ArrayList<>();
        JsonNode elements = root.path("elements");
        for (JsonNode e : elements) {
            if (!"way".equals(e.path("type").asText())) continue;
            RoadSegment s = segment(e, importedAt);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static RoadSegment segment(JsonNode e, LocalDateTime importedAt) {
        JsonNode tags = e.path("tags");
        String highway = text(tags, "highway");
        if (highway == null) return null;
        boolean main = MAIN_ROADS.contains(highway);
        boolean pedestrianStreet = "pedestrian".equals(highway);
        if (!main && !MINOR_ROADS.contains(highway) && !pedestrianStreet) return null;
        String motor = text(tags, "motor_vehicle", text(tags, "vehicle", text(tags, "access")));
        // Private roads (apartment and campus lanes) aren't public rules - leave them out.
        if ("private".equals(motor)) return null;
        // Closed to motor vehicles: a pedestrian street, or motor_vehicle / vehicle / access = no.
        boolean noMotor = pedestrianStreet ? (motor == null || !ALLOWED.contains(motor)) : "no".equals(motor);
        String noMotorHours = noMotor ? null
                : hours(text(tags, "motor_vehicle:conditional", text(tags, "access:conditional")));
        if (noMotorHours != null) noMotor = true;
        String hgv = text(tags, "hgv");
        boolean noHeavy = "no".equals(hgv);
        String noHeavyHours = noHeavy ? null : hours(text(tags, "hgv:conditional"));
        if (noHeavyHours != null) noHeavy = true;

        JsonNode geom = e.path("geometry");
        if (!geom.isArray() || geom.size() < 2) return null;
        double[] lats = new double[geom.size()];
        double[] lngs = new double[geom.size()];
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        int n = 0;
        for (JsonNode p : geom) {
            if (!p.has("lat") || !p.has("lon")) continue;
            lats[n] = p.get("lat").asDouble();
            lngs[n] = p.get("lon").asDouble();
            minLat = Math.min(minLat, lats[n]);
            maxLat = Math.max(maxLat, lats[n]);
            minLng = Math.min(minLng, lngs[n]);
            maxLng = Math.max(maxLng, lngs[n]);
            n++;
        }
        if (n < 2) return null;
        if (n < lats.length) {
            lats = java.util.Arrays.copyOf(lats, n);
            lngs = java.util.Arrays.copyOf(lngs, n);
        }

        int oneway = oneway(tags, highway);
        Integer maxspeed = SpeedLimitLaw.parseMaxspeed(text(tags, "maxspeed"));
        Integer forward = SpeedLimitLaw.parseMaxspeed(text(tags, "maxspeed:forward"));
        Integer backward = SpeedLimitLaw.parseMaxspeed(text(tags, "maxspeed:backward"));
        if (!main && oneway == 0 && maxspeed == null && forward == null && backward == null && !noMotor && !noHeavy) {
            return null; // nothing on this small road that changes the rules
        }
        Integer lanes = null;
        try {
            String l = text(tags, "lanes");
            if (l != null) lanes = Integer.parseInt(l.split(";")[0].trim());
        } catch (NumberFormatException ignored) {
            // "2;3" and other odd values: leave unknown
        }
        boolean divided = "yes".equals(text(tags, "dual_carriageway"))
                || (oneway != 0 && (highway.startsWith("motorway") || highway.startsWith("trunk")))
                || (oneway != 0 && lanes != null && lanes >= 2
                    && (highway.equals("primary") || highway.equals("secondary")));

        String name = text(tags, "name:en", text(tags, "name"));
        String ref = text(tags, "ref");
        return RoadSegment.builder()
                .id(e.path("id").asLong())
                .name(clip(name, 160))
                .ref(clip(ref, 60))
                .highway(highway)
                .maxspeedKmh(maxspeed)
                .maxspeedForwardKmh(forward)
                .maxspeedBackwardKmh(backward)
                .oneway(oneway)
                .cyclesBothWays("no".equals(text(tags, "oneway:bicycle")) || "opposite".equals(text(tags, "cycleway")))
                .divided(divided)
                .lanes(lanes)
                .noMotorVehicles(noMotor)
                .noMotorHours(noMotorHours)
                .noHeavyVehicles(noHeavy)
                .noHeavyHours(noHeavyHours)
                .geometry(Polyline.encode(lats, lngs))
                .minLat(minLat).maxLat(maxLat).minLng(minLng).maxLng(maxLng)
                .importedAt(importedAt)
                .build();
    }

    /** OSM's one-way rules: oneway=yes/-1, and motorways and roundabouts are one-way unless tagged otherwise. */
    static int oneway(JsonNode tags, String highway) {
        String v = text(tags, "oneway");
        if (v != null) {
            v = v.toLowerCase(Locale.ROOT);
            if (v.equals("yes") || v.equals("true") || v.equals("1")) return 1;
            if (v.equals("-1") || v.equals("reverse")) return -1;
            return 0; // "no", "reversible", "alternating"
        }
        String junction = text(tags, "junction");
        if ("roundabout".equals(junction) || "circular".equals(junction)) return 1;
        if ("motorway".equals(highway)) return 1;
        return 0;
    }

    /**
     * The hours of a timed ban, from an OSM conditional value such as
     * "no @ (08:00-11:00,17:00-21:00)" - only daily hour ranges are read;
     * anything with days, dates or other conditions gives null (ignored),
     * so a ban is never applied at the wrong time.
     */
    public static String hours(String conditional) {
        if (conditional == null) return null;
        String v = conditional.trim();
        if (!v.toLowerCase(Locale.ROOT).startsWith("no")) return null;
        int at = v.indexOf('@');
        if (at < 0) return null;
        String when = v.substring(at + 1).trim();
        if (when.startsWith("(") && when.endsWith(")")) when = when.substring(1, when.length() - 1).trim();
        if (!when.matches("\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}(\\s*[,;]\\s*\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2})*")) return null;
        String clean = when.replaceAll("\\s+", "").replace(';', ',');
        return clean.length() > 80 ? null : clean;
    }

    private static String text(JsonNode tags, String key) {
        return text(tags, key, null);
    }

    private static String text(JsonNode tags, String key, String fallback) {
        JsonNode v = tags.get(key);
        if (v == null || v.isNull()) return fallback;
        String s = v.asText().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
