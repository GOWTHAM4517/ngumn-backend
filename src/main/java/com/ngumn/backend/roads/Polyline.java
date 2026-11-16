package com.ngumn.backend.roads;

/**
 * Google's encoded polyline format (precision 5, about 1 m): a road's shape
 * as one short string instead of hundreds of numbers. The same format the
 * map libraries understand.
 */
public final class Polyline {

    private Polyline() {
    }

    public static String encode(double[] lats, double[] lngs) {
        StringBuilder out = new StringBuilder(lats.length * 6);
        long prevLat = 0;
        long prevLng = 0;
        for (int i = 0; i < lats.length; i++) {
            long lat = Math.round(lats[i] * 1e5);
            long lng = Math.round(lngs[i] * 1e5);
            put(out, lat - prevLat);
            put(out, lng - prevLng);
            prevLat = lat;
            prevLng = lng;
        }
        return out.toString();
    }

    /** {lats, lngs}. */
    public static double[][] decode(String encoded) {
        int n = 0;
        double[] lats = new double[Math.max(2, encoded.length() / 4)];
        double[] lngs = new double[lats.length];
        int i = 0;
        long lat = 0;
        long lng = 0;
        while (i < encoded.length()) {
            long[] r = take(encoded, i);
            lat += r[0];
            i = (int) r[1];
            r = take(encoded, i);
            lng += r[0];
            i = (int) r[1];
            if (n == lats.length) {
                lats = java.util.Arrays.copyOf(lats, n * 2);
                lngs = java.util.Arrays.copyOf(lngs, n * 2);
            }
            lats[n] = lat / 1e5;
            lngs[n] = lng / 1e5;
            n++;
        }
        return new double[][]{java.util.Arrays.copyOf(lats, n), java.util.Arrays.copyOf(lngs, n)};
    }

    private static void put(StringBuilder out, long v) {
        long s = v < 0 ? ~(v << 1) : (v << 1);
        while (s >= 0x20) {
            out.append((char) ((0x20 | (s & 0x1f)) + 63));
            s >>= 5;
        }
        out.append((char) (s + 63));
    }

    /** {value, next index}. */
    private static long[] take(String s, int i) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = s.charAt(i++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && i < s.length());
        long v = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new long[]{v, i};
    }
}
