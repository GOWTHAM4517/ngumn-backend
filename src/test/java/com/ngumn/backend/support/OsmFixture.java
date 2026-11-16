package com.ngumn.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small piece of the Vijayawada road map in the exact shape the Overpass
 * API answers ("[out:json] ... out tags geom;"), built from metre offsets
 * around Benz Circle so tests can put a vehicle precisely on a carriageway.
 *
 * Layout (x = metres east of Benz Circle, y = metres north):
 * - Bandar Road: primary, two-way, from (0,0) east to (800,0).
 * - NH16: two one-way carriageways (trunk, 3 lanes each) - northbound at
 *   x=-500 drawn south to north, southbound at x=-485 drawn north to south,
 *   from y=-1000 to y=1000.
 * - A one-way residential lane at y=400 from x=0 east to x=300 (cycles both ways).
 * - Patamata School Road: tertiary, posted 40, at x=600 from y=-300 to y=300.
 * - Ring Road: secondary, posted "60 km/h", at y=-600 from x=-200 to x=700.
 * - An untagged residential street and a footway - neither should be kept.
 * - Temple Street: pedestrian, at x=1000 from y=-200 to y=200 (no vehicles).
 * - Market Road: residential, hgv=no, at y=800 from x=600 to x=1000.
 * - Auto Nagar Road: tertiary, "hgv:conditional"="no @ (08:00-11:00,17:00-21:00)", at x=1400.
 * - A private apartment lane (access=private) - not kept.
 */
public final class OsmFixture {

    public static final double LAT = 16.4990;
    public static final double LNG = 80.6560;

    private OsmFixture() {
    }

    public static double lat(double yMeters) {
        return LAT + yMeters / 111_320.0;
    }

    public static double lng(double xMeters) {
        return LNG + xMeters / (111_320.0 * Math.cos(Math.toRadians(LAT)));
    }

    public static String json() {
        List<String> ways = new ArrayList<>();
        ways.add(way(1001, Map.of("highway", "primary", "name", "Bandar Road", "lanes", "2"), line(0, 0, 800, 0, 5)));
        ways.add(way(1002, Map.of("highway", "trunk", "ref", "NH16", "name", "Chennai-Kolkata Highway", "oneway", "yes", "lanes", "3"),
                line(-500, -1000, -500, 1000, 9)));
        ways.add(way(1003, Map.of("highway", "trunk", "ref", "NH16", "name", "Chennai-Kolkata Highway", "oneway", "yes", "lanes", "3"),
                line(-485, 1000, -485, -1000, 9)));
        ways.add(way(1004, Map.of("highway", "residential", "name", "Gunadala Lane", "oneway", "yes", "oneway:bicycle", "no"),
                line(0, 400, 300, 400, 4)));
        ways.add(way(1005, Map.of("highway", "tertiary", "name", "Patamata School Road", "maxspeed", "40"),
                line(600, -300, 600, 300, 5)));
        ways.add(way(1006, Map.of("highway", "secondary", "name", "Ring Road", "maxspeed", "60 km/h"),
                line(-200, -600, 700, -600, 6)));
        ways.add(way(1007, Map.of("highway", "residential", "name", "Quiet Street"), line(200, 200, 200, 350, 3)));
        ways.add(way(1008, Map.of("highway", "footway"), line(100, 100, 150, 150, 3)));
        ways.add(way(1009, Map.of("highway", "pedestrian", "name", "Temple Street"), line(1000, -200, 1000, 200, 5)));
        ways.add(way(1010, Map.of("highway", "residential", "name", "Market Road", "hgv", "no"), line(600, 800, 1000, 800, 5)));
        ways.add(way(1011, Map.of("highway", "tertiary", "name", "Auto Nagar Road", "hgv:conditional", "no @ (08:00-11:00,17:00-21:00)"),
                line(1400, -300, 1400, 300, 5)));
        ways.add(way(1012, Map.of("highway", "residential", "name", "Apartment Lane", "access", "private", "oneway", "yes"), line(300, 600, 400, 600, 3)));
        return "{\"version\":0.6,\"generator\":\"Overpass API\",\"elements\":[" + String.join(",", ways) + "]}";
    }

    private static double[][] line(double x0, double y0, double x1, double y1, int points) {
        double[][] out = new double[points][2];
        for (int i = 0; i < points; i++) {
            double t = i / (double) (points - 1);
            out[i][0] = lat(y0 + (y1 - y0) * t);
            out[i][1] = lng(x0 + (x1 - x0) * t);
        }
        return out;
    }

    private static String way(long id, Map<String, String> tags, double[][] pts) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"way\",\"id\":").append(id).append(",\"tags\":{");
        boolean first = true;
        for (Map.Entry<String, String> t : tags.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(t.getKey()).append("\":\"").append(t.getValue()).append('"');
        }
        sb.append("},\"geometry\":[");
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "{\"lat\":%.7f,\"lon\":%.7f}", pts[i][0], pts[i][1]));
        }
        sb.append("]}");
        return sb.toString();
    }
}
