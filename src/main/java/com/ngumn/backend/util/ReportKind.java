package com.ngumn.backend.util;

import com.ngumn.backend.entity.ReportType;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a hazard report is about, in more detail than its ReportType - the
 * enum only has six values, so several Quick Alerts share one ("Animal on
 * road" and "Road work / lane closed" are both ROAD_HAZARD) and the
 * description tells them apart. Words are matched whole ("drain" isn't
 * "rain", "street light" isn't a "tree").
 *
 * Each kind says:
 *
 * - how long a report of it stays up on its own (`lifetime`). Things that
 *   pass - a traffic jam, an accident, an animal, an ambulance - clear by
 *   themselves within hours or minutes. A pothole or road work stays until
 *   it's fixed (`lasting`): every "it's still there" (a like, or someone
 *   reporting it again) restarts its clock, with no limit, and two people
 *   saying "not there anymore" clear it.
 * - how close two reports of it must be to be the same thing
 *   (`sameSpotMeters`). A second pothole report within 50 m is the same
 *   pothole, a second traffic jam within 400 m is the same jam, and rain
 *   within 2 km is the same rain - so many people reporting one thing make
 *   one report, not a pile of them (RoadReportService.submit). Reports of a
 *   catch-all kind (`general`: "Other", a road hazard or an emergency that
 *   isn't one of the named ones) are only the same thing when they say the
 *   same thing.
 *
 * The app groups reports with the same rules (ngumn-app/src/lib/report-kind.ts)
 * - keep the two in sync.
 */
public enum ReportKind {
    POTHOLE(Duration.ofDays(60), true, 50, false),
    ROAD_WORK(Duration.ofDays(7), true, 150, false),
    ACCIDENT(Duration.ofHours(2), false, 150, false),
    TRAFFIC_JAM(Duration.ofHours(1), false, 400, false),
    SIGNAL_DOWN(Duration.ofHours(6), false, 100, false),
    AMBULANCE(Duration.ofMinutes(10), false, 300, false),
    EMERGENCY(Duration.ofMinutes(30), false, 100, true),
    ANIMAL(Duration.ofMinutes(30), false, 100, false),
    FLOODING(Duration.ofHours(6), false, 150, false),
    FALLEN_TREE(Duration.ofHours(12), false, 80, false),
    SLIPPERY(Duration.ofHours(4), false, 100, false),
    DEBRIS(Duration.ofHours(4), false, 60, false),
    ROAD_HAZARD(Duration.ofHours(4), false, 60, true),
    RAIN(Duration.ofHours(2), false, 2000, false),
    FOG(Duration.ofHours(2), false, 2000, false),
    WIND(Duration.ofHours(2), false, 2000, false),
    OTHER(Duration.ofHours(2), false, 100, true);

    /** How long a report of this kind stays up on its own, from when it was made (or last confirmed). */
    public final Duration lifetime;
    /** Stays until it's fixed: every confirmation restarts its clock, with no limit. */
    public final boolean lasting;
    /** Reports of this kind this close together are the same thing. */
    public final double sameSpotMeters;
    /** A catch-all kind: two reports are only the same thing when their descriptions match too. */
    public final boolean general;

    ReportKind(Duration lifetime, boolean lasting, double sameSpotMeters, boolean general) {
        this.lifetime = lifetime;
        this.lasting = lasting;
        this.sameSpotMeters = sameSpotMeters;
        this.general = general;
    }

    private static final Pattern AMBULANCE_WORDS = words("ambulance|ambulances|emergency vehicle|give way|make way|siren|fire engine|fire truck");
    private static final Pattern SIGNAL_WORDS = words("signal|signals|traffic light|traffic lights");
    private static final Pattern ANIMAL_WORDS = words("animal|animals|cow|cows|buffalo|buffaloes|cattle|dog|dogs|goat|goats|bull|bulls");
    private static final Pattern ROAD_WORK_WORDS = words("road work|road works|roadwork|roadworks|construction|lane closed|road closed|digging|repair|repairs");
    private static final Pattern FLOOD_WORDS = words("flood|floods|flooded|flooding|waterlogged|waterlogging|water logged|water logging|water-logged");
    private static final Pattern TREE_WORDS = words("tree|trees");
    private static final Pattern SLIPPERY_WORDS = words("slippery|oil|oil spill|mud|muddy|wet road");
    private static final Pattern DEBRIS_WORDS = words("debris|stone|stones|rock|rocks|sand|gravel|brick|bricks|garbage|glass");
    private static final Pattern RAIN_WORDS = words("rain|rains|raining|rainy|heavy rain|downpour");
    private static final Pattern FOG_WORDS = words("fog|foggy|mist|misty|smog");
    private static final Pattern WIND_WORDS = words("wind|winds|windy|storm|stormy");

    private static Pattern words(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }

    public static ReportKind of(ReportType type, String description) {
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (type == null) return ROAD_HAZARD;
        return switch (type) {
            case POTHOLE -> POTHOLE;
            case ACCIDENT -> ACCIDENT;
            // "Ambulance behind" - it has passed within minutes. Any other emergency lasts longer.
            case EMERGENCY -> has(d, AMBULANCE_WORDS) ? AMBULANCE : EMERGENCY;
            // "Traffic signal not working" is filed as a traffic jam.
            case TRAFFIC_JAM -> has(d, SIGNAL_WORDS) ? SIGNAL_DOWN : TRAFFIC_JAM;
            case ROAD_HAZARD -> {
                if (has(d, ANIMAL_WORDS)) yield ANIMAL;
                if (has(d, ROAD_WORK_WORDS)) yield ROAD_WORK;
                if (has(d, FLOOD_WORDS)) yield FLOODING;
                if (has(d, TREE_WORDS)) yield FALLEN_TREE;
                if (has(d, SLIPPERY_WORDS)) yield SLIPPERY;
                if (has(d, DEBRIS_WORDS)) yield DEBRIS;
                yield ROAD_HAZARD;
            }
            // Heavy rain, fog, high winds...
            default -> {
                if (has(d, RAIN_WORDS)) yield RAIN;
                if (has(d, FOG_WORDS)) yield FOG;
                if (has(d, WIND_WORDS)) yield WIND;
                yield OTHER;
            }
        };
    }

    /**
     * True if two reports are about the same thing (their places aside): the
     * same kind - and for a catch-all kind, the same words too (a blank
     * description matches anything of that kind).
     */
    public static boolean sameThing(ReportType typeA, String descriptionA, ReportType typeB, String descriptionB) {
        // Demo Mode's sample reports and real ones are never the same thing.
        if (isSample(descriptionA) != isSample(descriptionB)) return false;
        ReportKind a = of(typeA, descriptionA);
        if (a != of(typeB, descriptionB)) return false;
        if (!a.general) return true;
        String da = plain(descriptionA), db = plain(descriptionB);
        return da.isEmpty() || db.isEmpty() || da.equals(db);
    }

    /** Demo Mode's sample reports say so ("(Sample report - not a real hazard)" - see DemoSimulatorService). */
    public static boolean isSample(String description) {
        return description != null && description.trim().toLowerCase(Locale.ROOT).startsWith("(sample report");
    }

    private static String plain(String description) {
        return description == null ? "" : description.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean has(String text, Pattern words) {
        return words.matcher(text).find();
    }
}
