package com.ngumn.backend.util;

import com.ngumn.backend.entity.User;

/**
 * What each person wants to hear about in the area around them - set in
 * the app under Settings > Notifications. Nothing set means the defaults,
 * so accounts made before these settings existed hear about everything
 * within 2 km.
 *
 * These only filter the "in your area" notices (a hazard reported 1.2 km
 * away, someone speeding two streets over). A warning about a danger in
 * your own path - a speeding car coming your way, a hazard on the road
 * ahead, an ambulance behind you - always comes through.
 */
public final class NotificationPrefs {

    public static final int DEFAULT_RADIUS_M = 2000;
    public static final int MIN_RADIUS_M = 200;
    public static final int MAX_RADIUS_M = 10_000;

    private NotificationPrefs() {
    }

    /** How far around them this person wants to hear about reports and rule-breakers. */
    public static int radiusMeters(User u) {
        Integer r = u != null ? u.getAlertRadiusM() : null;
        return r == null ? DEFAULT_RADIUS_M : clamp(r);
    }

    public static int clamp(int radiusM) {
        return Math.max(MIN_RADIUS_M, Math.min(MAX_RADIUS_M, radiusM));
    }

    /** Wants to hear about hazards reported nearby (on unless turned off). */
    public static boolean hazards(User u) {
        return u == null || !Boolean.FALSE.equals(u.getNotifyHazards());
    }

    /** Wants to hear about rule-breakers nearby (on unless turned off). */
    public static boolean ruleBreakers(User u) {
        return u == null || !Boolean.FALSE.equals(u.getNotifyRuleBreakers());
    }
}
