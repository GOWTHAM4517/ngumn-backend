package com.ngumn.backend.roads;

import com.ngumn.backend.entity.TravelMode;

import java.util.List;

/**
 * Works out the road under a GPS point and the rules that apply there. Pure
 * logic over a RoadNetwork - no database, no Spring - so it is tested with
 * real-shaped OpenStreetMap data (RoadRulesTest).
 *
 * Matching: among mapped roads within 20 m (plus up to 10 m of the fix's
 * own GPS error), a road whose allowed direction disagrees with the heading
 * by more than 45 degrees gets a 30 m penalty, then the lowest score wins.
 * This keeps a car on the correct side of a divided highway (the two sides
 * are separate one-way roads ~15 m apart) and off a side street it is only
 * passing.
 *
 * Wrong way: only when the nearest road within 12 m is one-way, the heading
 * is at least 150 degrees off its direction, the vehicle is moving, and no
 * road nearly as close (within 8 m more) allows the direction it is going. The rule monitor also
 * needs two such updates in a row. A noisy fix next to the other carriageway
 * never counts.
 */
public final class RoadRules {

    public static final double MATCH_METERS = 20;
    public static final double WRONG_WAY_METERS = 12;
    public static final double WRONG_WAY_ANGLE = 150;
    public static final double MOVING_KMH = 8;
    /** A road that allows the direction and is at most this much further than the one-way road clears the driver. */
    public static final double ALLOW_MARGIN_METERS = 8;

    private RoadRules() {
    }

    /**
     * The road and its rules at a point. heading may be null (unknown);
     * accuracyMeters may be null. mode null = car.
     */
    public static RoadInfo at(RoadNetwork net, double lat, double lng, Double heading, double speedKmh,
                              Double accuracyMeters, TravelMode mode) {
        if (mode == null) mode = TravelMode.CAR;
        boolean cycling = mode == TravelMode.CYCLE;
        double extra = accuracyMeters == null ? 0 : Math.max(0, Math.min(10, accuracyMeters - 5));
        List<RoadNetwork.Hit> hits = net.near(lat, lng, 25 + extra);

        RoadNetwork.Hit best = null;
        double bestScore = Double.MAX_VALUE;
        for (RoadNetwork.Hit h : hits) {
            if (h.distanceMeters > MATCH_METERS + extra) continue;
            double score = h.distanceMeters;
            if (heading != null && speedKmh >= MOVING_KMH && h.mismatch(heading, cycling) > 45) score += 30;
            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }

        boolean wrongWay = false;
        if (heading != null && speedKmh >= MOVING_KMH && mode != TravelMode.WALK && !hits.isEmpty()) {
            RoadNetwork.Hit nearest = hits.get(0);
            boolean onewayHere = nearest.road.row().getOneway() != null && nearest.road.row().getOneway() != 0
                    && !(cycling && Boolean.TRUE.equals(nearest.road.row().getCyclesBothWays()));
            if (onewayHere && nearest.distanceMeters <= WRONG_WAY_METERS
                    && nearest.mismatch(heading, cycling) >= WRONG_WAY_ANGLE) {
                // A road almost as close that allows this direction (the other
                // carriageway when GPS drifts, a parallel service road) means
                // it can't be told apart - don't accuse anyone.
                boolean someRoadAllows = false;
                for (RoadNetwork.Hit h : hits) {
                    if (h.distanceMeters <= nearest.distanceMeters + ALLOW_MARGIN_METERS
                            && h.mismatch(heading, cycling) <= 45) {
                        someRoadAllows = true;
                        break;
                    }
                }
                wrongWay = !someRoadAllows;
                if (wrongWay) best = nearest;
            }
        }

        if (best == null) {
            return unmapped(net.size() > 0, mode);
        }
        RoadSegment s = best.road.row();
        SpeedLimitLaw.RoadCategory cat = SpeedLimitLaw.category(s);
        Integer legal = SpeedLimitLaw.legalLimit(mode, cat);
        Integer posted = posted(s, heading == null || best.forward(heading));
        Integer limit = legal;
        String source = "LAW";
        String note = legal == null ? "No speed limit when walking or cycling"
                : "Legal limit for " + mode.withArticle() + " on a " + cat.label() + " (" + SpeedLimitLaw.SOURCE_LAW + ")";
        if (legal != null && posted != null && posted <= legal) {
            limit = posted;
            source = "SIGN";
            note = "Posted limit on this road (OpenStreetMap)";
        }
        return RoadInfo.builder()
                .roadId(s.getId())
                .mapped(true)
                .name(displayName(s))
                .ref(s.getRef())
                .roadType(roadType(s))
                .category(cat.label())
                .limitKmh(limit)
                .limitSource(limit == null ? null : source)
                .limitNote(note)
                .overAtKmh(limit == null ? null : round1(SpeedLimitLaw.threshold(limit)))
                .oneway(s.getOneway() != null && s.getOneway() != 0)
                .wrongWay(wrongWay)
                .forward(heading == null || best.forward(heading))
                .distanceMeters(round1(best.distanceMeters))
                .build();
    }

    /**
     * Not on a mapped road: the legal limit for "other roads" still applies.
     * When the region's roads aren't loaded at all, the limit is unknown.
     */
    public static RoadInfo unmapped(boolean networkLoaded, TravelMode mode) {
        if (!networkLoaded) {
            return RoadInfo.builder().mapped(false).name(null).build();
        }
        Integer legal = SpeedLimitLaw.legalLimit(mode, SpeedLimitLaw.RoadCategory.OTHER);
        return RoadInfo.builder()
                .mapped(false)
                .name("Local road")
                .roadType("Local road")
                .category(SpeedLimitLaw.RoadCategory.OTHER.label())
                .limitKmh(legal)
                .limitSource(legal == null ? null : "LAW")
                .limitNote(legal == null ? null : "Legal limit for " + mode.withArticle() + " on a city / other road ("
                        + SpeedLimitLaw.SOURCE_LAW + ")")
                .overAtKmh(legal == null ? null : round1(SpeedLimitLaw.threshold(legal)))
                .build();
    }

    /** The posted limit for the direction of travel, if the map has one. */
    static Integer posted(RoadSegment s, boolean forward) {
        Integer dir = forward ? s.getMaxspeedForwardKmh() : s.getMaxspeedBackwardKmh();
        if (dir != null) return dir;
        return s.getMaxspeedKmh();
    }

    public static String displayName(RoadSegment s) {
        if (s.getName() != null && !s.getName().isBlank()) return s.getName();
        if (s.getRef() != null && !s.getRef().isBlank()) return s.getRef();
        return roadType(s);
    }

    public static String roadType(RoadSegment s) {
        String ref = s.getRef() == null ? "" : s.getRef().toUpperCase();
        if (ref.startsWith("NH")) return "National highway";
        if (ref.startsWith("SH")) return "State highway";
        String h = s.getHighway() == null ? "" : s.getHighway();
        if (h.startsWith("motorway")) return "Expressway";
        if (h.startsWith("trunk")) return "Highway";
        if (h.startsWith("primary") || h.startsWith("secondary")) return "Main road";
        if (h.startsWith("tertiary")) return "Link road";
        return "Local road";
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
