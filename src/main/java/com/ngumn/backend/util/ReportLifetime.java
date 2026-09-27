package com.ngumn.backend.util;

import com.ngumn.backend.entity.ReportType;

import java.time.Duration;
import java.util.Locale;

/**
 * How long a hazard report stays on the road before it clears by itself.
 *
 * A traffic jam is usually gone within the hour, but a pothole is still
 * there next week - so each kind of report gets its own lifetime. Several
 * Quick Alerts share a backend type (the enum only has six values), so the
 * description tells them apart: "Animal on road" and "Road work / lane
 * closed" are both ROAD_HAZARD but last very different times.
 *
 * Every "yes, it's still there" from someone nearby restarts the clock
 * (see CommunityService.voteOnReport), so a hazard people keep confirming
 * stays up, and one nobody sees any more quietly disappears.
 */
public final class ReportLifetime {

    /** The longest lifetime below - bounds "recent reports" queries. */
    public static final Duration MAX = Duration.ofDays(7);

    private ReportLifetime() {
    }

    public static Duration of(ReportType type, String description) {
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (type == null) return Duration.ofHours(2);
        switch (type) {
            case TRAFFIC_JAM:
                // "Traffic signal not working" is filed as a traffic jam.
                return has(d, "signal") ? Duration.ofHours(6) : Duration.ofHours(1);
            case ACCIDENT:
                return Duration.ofHours(3);
            case POTHOLE:
                return Duration.ofDays(7);
            case EMERGENCY:
                // "Ambulance behind" - it has passed within minutes.
                return Duration.ofMinutes(30);
            case ROAD_HAZARD:
                if (has(d, "animal", "cow", "buffalo", "cattle", "dog", "goat")) return Duration.ofHours(1);
                if (has(d, "road work", "roadwork", "construction", "lane closed", "road closed", "digging", "repair")) {
                    return Duration.ofDays(3);
                }
                if (has(d, "flood", "waterlog", "water logged", "water-logged")) return Duration.ofHours(6);
                if (has(d, "tree")) return Duration.ofHours(12);
                return Duration.ofHours(4);
            case OTHER:
            default:
                // Heavy rain, fog, high winds...
                return Duration.ofHours(2);
        }
    }

    private static boolean has(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }
}
