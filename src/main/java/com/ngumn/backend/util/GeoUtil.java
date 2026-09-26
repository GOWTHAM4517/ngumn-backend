package com.ngumn.backend.util;

/**
 * Geometry helpers used for nearby-vehicle detection, speed-limit lookups
 * and the traffic-rule monitor. Prototype-grade: distances use the
 * haversine formula, and segment maths uses a flat (equirectangular)
 * projection, which is accurate enough over the few kilometres a rule
 * zone covers.
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoUtil() {
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /** Compass bearing from point 1 to point 2, in degrees (0 = north, 90 = east). */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** Smallest angle between two bearings, 0..180 degrees. */
    public static double angleDifference(double a, double b) {
        double d = Math.abs(((a - b) % 360.0 + 360.0) % 360.0);
        return d > 180.0 ? 360.0 - d : d;
    }

    /**
     * Where point P lies relative to segment A-B: t is the position along
     * the segment (0 at A, 1 at B, outside 0..1 beyond the ends) and
     * distanceMeters is the distance from P to the nearest point of the
     * segment.
     */
    public static SegmentHit projectOnSegment(double pLat, double pLon,
                                              double aLat, double aLon, double bLat, double bLon) {
        double metersPerDegLat = Math.toRadians(1) * EARTH_RADIUS_METERS;
        double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians((aLat + bLat) / 2));
        double bx = (bLon - aLon) * metersPerDegLon;
        double by = (bLat - aLat) * metersPerDegLat;
        double px = (pLon - aLon) * metersPerDegLon;
        double py = (pLat - aLat) * metersPerDegLat;
        double lengthSq = bx * bx + by * by;
        double t = lengthSq == 0 ? 0 : (px * bx + py * by) / lengthSq;
        double clamped = Math.max(0, Math.min(1, t));
        double dx = px - clamped * bx;
        double dy = py - clamped * by;
        return new SegmentHit(t, Math.sqrt(dx * dx + dy * dy));
    }

    public static final class SegmentHit {
        private final double t;
        private final double distanceMeters;

        public SegmentHit(double t, double distanceMeters) {
            this.t = t;
            this.distanceMeters = distanceMeters;
        }

        public double getT() {
            return t;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }
    }
}
