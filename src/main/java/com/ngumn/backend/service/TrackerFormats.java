package com.ngumn.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngumn.backend.exception.ApiException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * What GPS trackers send to /api/tracker, turned into a CarGuardService.Fix.
 *
 * <ol>
 *   <li>OsmAnd / Traccar Client in the query or a form:
 *       {@code ?id=…&lat=…&lon=…&speed=<knots>&bearing=…&timestamp=<epoch s|ms|ISO>&ignition=true&batt=80&alarm=powerCut}
 *       ({@code kmh=} instead of {@code speed=} for km/h)</li>
 *   <li>Traccar Client JSON: {@code {"device_id":…, "location":{"timestamp":…, "coords":{"latitude","longitude","speed"<m/s>,"heading"}, "battery":{"level":0-1}}}}</li>
 *   <li>A Traccar server forwarding positions / events (any tracker Traccar
 *       supports, e.g. GT06 hard-wired car trackers):
 *       {@code {"device":{"uniqueId":…}, "position":{"latitude","longitude","speed"<knots>,"course","fixTime","attributes":{"ignition","batteryLevel","alarm"}}, "event":{…}}}</li>
 * </ol>
 */
public final class TrackerFormats {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double KNOTS = 1.852;

    private TrackerFormats() {
    }

    public static CarGuardService.Fix parse(Map<String, String> params, String body) {
        if (body != null && body.trim().startsWith("{")) {
            Map<?, ?> json;
            try {
                json = JSON.readValue(body, Map.class);
            } catch (Exception e) {
                throw ApiException.badRequest("That isn't valid JSON.");
            }
            if (json.get("position") instanceof Map || json.get("device") instanceof Map) return traccarServer(json);
            if (json.get("location") instanceof Map) return traccarClient(json, params);
            return query(flat(json, params));
        }
        return query(params);
    }

    /** OsmAnd: everything in parameters. */
    static CarGuardService.Fix query(Map<String, String> p) {
        String id = first(p, "id", "deviceid", "device_id", "uniqueId");
        Double lat = num(first(p, "lat", "latitude"));
        Double lon = num(first(p, "lon", "lng", "longitude"));
        String location = p.get("location");
        if ((lat == null || lon == null) && location != null && location.contains(",")) {
            String[] parts = location.split(",");
            lat = num(parts[0]);
            lon = num(parts[1]);
        }
        if (lat == null || lon == null) throw ApiException.badRequest("Send lat and lon.");
        Double kmh = num(p.get("kmh"));
        Double knots = num(p.get("speed"));
        Double speed = kmh != null ? kmh : knots != null ? knots * KNOTS : null;
        Integer battery = percent(num(first(p, "batt", "battery", "batteryLevel")));
        return new CarGuardService.Fix(id, lat, lon, speed, num(first(p, "bearing", "heading", "course")),
                bool(p.get("ignition")), battery, time(p.get("timestamp")), first(p, "alarm", "event"));
    }

    /** Traccar Client 8+: device_id + location.coords (speed in m/s), battery level 0-1. */
    @SuppressWarnings("unchecked")
    static CarGuardService.Fix traccarClient(Map<?, ?> json, Map<String, String> params) {
        Map<String, Object> loc = (Map<String, Object>) json.get("location");
        Map<String, Object> coords = loc.get("coords") instanceof Map ? (Map<String, Object>) loc.get("coords") : Map.of();
        Map<String, Object> batt = loc.get("battery") instanceof Map ? (Map<String, Object>) loc.get("battery")
                : json.get("battery") instanceof Map ? (Map<String, Object>) json.get("battery") : Map.of();
        String id = str(json.get("device_id"));
        if (id == null) id = first(params, "id", "deviceid");
        Double lat = num(coords.get("latitude"));
        Double lon = num(coords.get("longitude"));
        if (lat == null || lon == null) throw ApiException.badRequest("Send location.coords.latitude and longitude.");
        Double ms = num(coords.get("speed"));
        Double level = num(batt.get("level"));
        Integer battery = level == null ? null : level <= 1 ? (int) Math.round(level * 100) : percent(level);
        Double heading = num(coords.get("heading"));
        if (heading != null && heading < 0) heading = null;
        return new CarGuardService.Fix(id, lat, lon, ms != null && ms >= 0 ? ms * 3.6 : null, heading,
                null, battery, time(str(loc.get("timestamp"))), str(loc.get("event")));
    }

    /** A Traccar server's position / event forwarding. */
    @SuppressWarnings("unchecked")
    static CarGuardService.Fix traccarServer(Map<?, ?> json) {
        Map<String, Object> device = json.get("device") instanceof Map ? (Map<String, Object>) json.get("device") : Map.of();
        Map<String, Object> pos = json.get("position") instanceof Map ? (Map<String, Object>) json.get("position") : Map.of();
        Map<String, Object> attrs = pos.get("attributes") instanceof Map ? (Map<String, Object>) pos.get("attributes") : Map.of();
        Map<String, Object> event = json.get("event") instanceof Map ? (Map<String, Object>) json.get("event") : Map.of();
        Map<String, Object> eventAttrs = event.get("attributes") instanceof Map ? (Map<String, Object>) event.get("attributes") : Map.of();
        String id = str(device.get("uniqueId"));
        Double lat = num(pos.get("latitude"));
        Double lon = num(pos.get("longitude"));
        if (lat == null || lon == null) throw ApiException.badRequest("Send position.latitude and longitude.");
        Double knots = num(pos.get("speed"));
        String alarm = str(attrs.get("alarm"));
        if (alarm == null) alarm = str(eventAttrs.get("alarm"));
        Object ign = attrs.get("ignition");
        return new CarGuardService.Fix(id, lat, lon, knots != null ? knots * KNOTS : null, num(pos.get("course")),
                ign == null ? null : bool(String.valueOf(ign)), percent(num(attrs.get("batteryLevel"))),
                time(str(pos.get("fixTime") != null ? pos.get("fixTime") : pos.get("deviceTime"))), alarm);
    }

    private static Map<String, String> flat(Map<?, ?> json, Map<String, String> params) {
        Map<String, String> m = new java.util.HashMap<>(params);
        json.forEach((k, v) -> {
            if (k != null && v != null && !(v instanceof Map)) m.put(String.valueOf(k), String.valueOf(v));
        });
        return m;
    }

    private static String first(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    static Double num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            double d = Double.parseDouble(String.valueOf(o).trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer percent(Double d) {
        return d == null ? null : (int) Math.round(Math.max(0, Math.min(100, d)));
    }

    private static Boolean bool(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("true") || t.equals("1") || t.equals("on") || t.equals("yes")) return true;
        if (t.equals("false") || t.equals("0") || t.equals("off") || t.equals("no")) return false;
        return null;
    }

    /** Epoch seconds or milliseconds, ISO with a zone, or "yyyy-MM-dd HH:mm:ss" (UTC, as OsmAnd sends it). Null if missing / unreadable. */
    static LocalDateTime time(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        ZoneId here = ZoneId.systemDefault();
        try {
            if (t.matches("\\d{9,14}(\\.\\d+)?")) {
                double v = Double.parseDouble(t);
                long ms = v > 1e12 ? (long) v : (long) (v * 1000);
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), here);
            }
            if (t.endsWith("Z") || t.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                String iso = t.matches(".*[+-]\\d{4}$") ? t.substring(0, t.length() - 2) + ":" + t.substring(t.length() - 2) : t;
                return OffsetDateTime.parse(iso.replace(' ', 'T')).atZoneSameInstant(here).toLocalDateTime();
            }
            LocalDateTime utc = LocalDateTime.parse(t.replace(' ', 'T').replaceAll("\\.\\d+$", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(here).toLocalDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
