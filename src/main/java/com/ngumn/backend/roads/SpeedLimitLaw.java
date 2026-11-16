package com.ngumn.backend.roads;

import com.ngumn.backend.entity.TravelMode;

import java.util.Locale;

/**
 * India's legal maximum speeds, from the Ministry of Road Transport and
 * Highways notification S.O. 1522(E) of 6 April 2018 (km/h):
 *
 * <pre>
 * Class of vehicle                          Expressway  4+ lane divided  Municipal / other roads
 * Cars, up to 8 seats besides driver (M1)       120          100              70
 * Buses, 9+ seats besides driver (M2, M3)        100           90              60
 * Goods vehicles (all N)                         80           80              60
 * Motorcycles                                    80*          80              60
 * Three-wheelers (autos)                          -           50              50
 * </pre>
 * (* only where motorcycles are allowed on the expressway.)
 *
 * States and cities may set lower limits with signs; a posted limit on the
 * map (OSM maxspeed) is used when it is lower than the class limit. The
 * notification also says no action is taken within 5% of the limit - that
 * is TOLERANCE.
 */
public final class SpeedLimitLaw {

    public static final String SOURCE_LAW = "MoRTH S.O. 1522(E), 6 Apr 2018";

    /** "No cognizance ... if the speed detected is within 5 per cent of the maximum speed." */
    public static final double TOLERANCE = 0.05;

    /** GPS speed is good to about +-2-3 km/h, so never call it over-speeding by less than this. */
    public static final double MIN_MARGIN_KMH = 3.0;

    public enum RoadCategory {
        EXPRESSWAY("expressway"),
        DIVIDED("4-lane divided road"),
        OTHER("city / other road");

        private final String label;

        RoadCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private SpeedLimitLaw() {
    }

    /** The legal maximum for this way of travelling on this kind of road, or null (no limit: walking, cycling). */
    public static Integer legalLimit(TravelMode mode, RoadCategory road) {
        if (mode == null) mode = TravelMode.CAR;
        int[] row = switch (mode) {
            case WALK, CYCLE -> null;
            case CAR, EMERGENCY -> new int[]{120, 100, 70};
            case BUS -> new int[]{100, 90, 60};
            case TRUCK -> new int[]{80, 80, 60};
            case BIKE -> new int[]{80, 80, 60};
            case AUTO -> new int[]{50, 50, 50};
        };
        if (row == null) return null;
        return row[road.ordinal()];
    }

    /** Speed above which it counts as over-speeding: the limit plus 5% (and at least 3 km/h for GPS error). */
    public static double threshold(int limitKmh) {
        return limitKmh + Math.max(MIN_MARGIN_KMH, limitKmh * TOLERANCE);
    }

    /** Clearly dangerous speed, worth warning the people around: 20% over the limit and at least 10 km/h. */
    public static double dangerous(int limitKmh) {
        return limitKmh + Math.max(10, limitKmh * 0.20);
    }

    /** The legal road category of a mapped road. */
    public static RoadCategory category(RoadSegment s) {
        String h = s.getHighway() == null ? "" : s.getHighway();
        if (h.equals("motorway")) return RoadCategory.EXPRESSWAY;
        boolean majorRoad = h.equals("motorway_link") || h.equals("trunk") || h.equals("primary") || h.equals("secondary");
        if (majorRoad && Boolean.TRUE.equals(s.getDivided())) {
            // A divided road counts as "4 lane and above" when each side has 2+ lanes
            // (or the lane count isn't mapped: national highways here are 4-6 lanes).
            Integer lanes = s.getLanes();
            boolean wide = lanes == null || (s.getOneway() != 0 ? lanes >= 2 : lanes >= 4);
            if (wide) return RoadCategory.DIVIDED;
        }
        return RoadCategory.OTHER;
    }

    /**
     * Reads an OSM maxspeed value: "50", "50 km/h", "50kmh", "30 mph". Zone
     * codes ("IN:urban"), "none", "signals", "walk" and anything else give
     * null - the legal class limit applies then.
     */
    public static Integer parseMaxspeed(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.contains(";")) {
            v = v.split(";")[0].trim();
        }
        boolean mph = v.endsWith("mph");
        String digits = v.replace("km/h", "").replace("kmh", "").replace("kph", "").replace("mph", "").trim();
        try {
            double n = Double.parseDouble(digits);
            if (mph) n *= 1.609344;
            int kmh = (int) Math.round(n);
            return kmh >= 5 && kmh <= 150 ? kmh : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
