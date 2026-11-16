package com.ngumn.backend.roads;

import com.ngumn.backend.util.GeoUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The region's roads in memory, with a grid index so "which road is this
 * GPS point on?" takes microseconds on every location update.
 *
 * Each road is split into its straight pieces (point to point); every piece
 * is filed under the grid cells (about 450 m square) it crosses. A lookup
 * checks the point's cell and the 8 around it, projects the point onto each
 * piece there and keeps the nearest piece of each road.
 */
public final class RoadNetwork {

    /** Grid cell size in degrees (~445 m north-south). */
    private static final double CELL = 0.004;

    public static final RoadNetwork EMPTY = new RoadNetwork(List.of());

    /** A road in memory: its row plus its decoded shape. */
    public static final class Road {
        final RoadSegment row;
        final double[] lats;
        final double[] lngs;

        Road(RoadSegment row, double[] lats, double[] lngs) {
            this.row = row;
            this.lats = lats;
            this.lngs = lngs;
        }

        public RoadSegment row() {
            return row;
        }

        public double[] lats() {
            return lats;
        }

        public double[] lngs() {
            return lngs;
        }
    }

    /** The nearest point of one road to a GPS point. */
    public static final class Hit {
        public final Road road;
        public final double distanceMeters;
        /** Compass bearing of the road at that point, in its drawing direction. */
        public final double bearing;

        Hit(Road road, double distanceMeters, double bearing) {
            this.road = road;
            this.distanceMeters = distanceMeters;
            this.bearing = bearing;
        }

        /** Directions vehicles may travel here: one bearing for a one-way road, both for a two-way one. */
        public double[] allowedBearings(boolean cycling) {
            int ow = road.row.getOneway() == null ? 0 : road.row.getOneway();
            if (ow == 0 || (cycling && Boolean.TRUE.equals(road.row.getCyclesBothWays()))) {
                return new double[]{bearing, (bearing + 180) % 360};
            }
            return new double[]{ow > 0 ? bearing : (bearing + 180) % 360};
        }

        /** Smallest angle between a heading and any allowed direction (0..180). */
        public double mismatch(double heading, boolean cycling) {
            double best = 180;
            for (double b : allowedBearings(cycling)) best = Math.min(best, GeoUtil.angleDifference(heading, b));
            return best;
        }

        /** True when the heading follows the drawing direction of the road (within 90 degrees). */
        public boolean forward(double heading) {
            return GeoUtil.angleDifference(heading, bearing) <= 90;
        }
    }

    private final List<Road> roads;
    private final Map<Long, Road> byId = new HashMap<>();
    private final Map<Long, List<int[]>> grid = new HashMap<>();
    private final long withMaxspeed;
    private final long oneways;

    public RoadNetwork(Collection<RoadSegment> rows) {
        List<Road> list = new ArrayList<>(rows.size());
        long ms = 0;
        long ow = 0;
        for (RoadSegment row : rows) {
            double[][] shape = Polyline.decode(row.getGeometry());
            if (shape[0].length < 2) continue;
            Road road = new Road(row, shape[0], shape[1]);
            int index = list.size();
            list.add(road);
            byId.put(row.getId(), road);
            if (row.getMaxspeedKmh() != null || row.getMaxspeedForwardKmh() != null || row.getMaxspeedBackwardKmh() != null) ms++;
            if (row.getOneway() != null && row.getOneway() != 0) ow++;
            for (int i = 0; i + 1 < road.lats.length; i++) {
                file(index, i, road.lats[i], road.lngs[i], road.lats[i + 1], road.lngs[i + 1]);
            }
        }
        this.roads = List.copyOf(list);
        this.withMaxspeed = ms;
        this.oneways = ow;
    }

    private void file(int road, int piece, double aLat, double aLng, double bLat, double bLng) {
        int r0 = cell(Math.min(aLat, bLat));
        int r1 = cell(Math.max(aLat, bLat));
        int c0 = cell(Math.min(aLng, bLng));
        int c1 = cell(Math.max(aLng, bLng));
        // A piece longer than ~10 cells is a data error (or a ferry line): file only its ends.
        if ((long) (r1 - r0 + 1) * (c1 - c0 + 1) > 100) {
            add(key(cell(aLat), cell(aLng)), road, piece);
            add(key(cell(bLat), cell(bLng)), road, piece);
            return;
        }
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) add(key(r, c), road, piece);
        }
    }

    private void add(long key, int road, int piece) {
        grid.computeIfAbsent(key, k -> new ArrayList<>(4)).add(new int[]{road, piece});
    }

    private static int cell(double deg) {
        return (int) Math.floor(deg / CELL);
    }

    private static long key(int row, int col) {
        return ((long) row << 32) ^ (col & 0xffffffffL);
    }

    public int size() {
        return roads.size();
    }

    public long withMaxspeed() {
        return withMaxspeed;
    }

    public long oneways() {
        return oneways;
    }

    public List<Road> roads() {
        return roads;
    }

    /** A road by its OSM way id, or null. */
    public Road road(Long id) {
        return id == null ? null : byId.get(id);
    }

    /** The nearest piece of every road within maxMeters of the point, nearest first. */
    public List<Hit> near(double lat, double lng, double maxMeters) {
        Map<Integer, Hit> best = new HashMap<>();
        int r = cell(lat);
        int c = cell(lng);
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                List<int[]> pieces = grid.get(key(r + dr, c + dc));
                if (pieces == null) continue;
                for (int[] p : pieces) {
                    Road road = roads.get(p[0]);
                    int i = p[1];
                    GeoUtil.SegmentHit hit = GeoUtil.projectOnSegment(lat, lng,
                            road.lats[i], road.lngs[i], road.lats[i + 1], road.lngs[i + 1]);
                    double d = hit.getDistanceMeters();
                    if (d > maxMeters) continue;
                    Hit prev = best.get(p[0]);
                    if (prev == null || d < prev.distanceMeters) {
                        double bearing = GeoUtil.bearingDegrees(road.lats[i], road.lngs[i], road.lats[i + 1], road.lngs[i + 1]);
                        best.put(p[0], new Hit(road, d, bearing));
                    }
                }
            }
        }
        List<Hit> out = new ArrayList<>(best.values());
        out.sort((a, b) -> Double.compare(a.distanceMeters, b.distanceMeters));
        return out;
    }
}
