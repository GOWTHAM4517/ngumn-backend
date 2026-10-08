package com.ngumn.backend.util;

import com.ngumn.backend.entity.ReportType;

import java.time.Duration;

/**
 * How long a hazard report stays on the road before it clears by itself -
 * see ReportKind for the list. In short: things that pass (a traffic jam,
 * an accident, an animal, an ambulance) clear by themselves within hours
 * or minutes; a pothole or road work stays until it's fixed.
 *
 * A like ("it's there") or someone reporting the same thing again
 * restarts the clock (ReportFeedbackService), so a hazard people keep
 * seeing stays up and one nobody sees any more quietly disappears.
 *
 * Rule-breaker reports are about something that already happened - the
 * vehicle has moved on - so they're shown for COMPLAINT and no longer.
 */
public final class ReportLifetime {

    /** The longest lifetime (a pothole's) - bounds "recent reports" queries. */
    public static final Duration MAX = Duration.ofDays(60);

    /** How long a rule-breaker report is shown to people nearby. */
    public static final Duration COMPLAINT = Duration.ofHours(2);

    private ReportLifetime() {
    }

    /** Demo Mode's sample reports clear within this, whatever they are. */
    public static final Duration SAMPLE = Duration.ofHours(2);

    public static Duration of(ReportType type, String description) {
        Duration life = ReportKind.of(type, description).lifetime;
        return ReportKind.isSample(description) && life.compareTo(SAMPLE) > 0 ? SAMPLE : life;
    }

    /** Stays until it's fixed (a pothole, road work) rather than clearing after a while - never a sample. */
    public static boolean lasting(ReportType type, String description) {
        return ReportKind.of(type, description).lasting && !ReportKind.isSample(description);
    }
}
