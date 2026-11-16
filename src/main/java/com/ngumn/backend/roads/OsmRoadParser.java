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

    /** Smaller roads, kept only when the map gives them a speed limit or a one-way direction. */
    public static final Set<String> MINOR_ROADS = Set.of(
            "unclassified", "residential", "living_street", "service", "road");

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
        if (!main && !MINOR_ROADS.contains(highway)) return null;
        String access = text(tags, "motor_vehicle", text(tags, "access"));
        if ("no".equals(access) || "private".equals(access)) return null;

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
        if (!main && oneway == 0 && maxspeed == null && forward == null && backward == null) {
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
