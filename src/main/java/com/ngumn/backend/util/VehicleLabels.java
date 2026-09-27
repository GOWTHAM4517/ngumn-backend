package com.ngumn.backend.util;

import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.entity.Vehicle;

import java.util.Locale;

/**
 * Plain-language names for vehicles in messages people read or hear -
 * "a car (AP 16 BX 2231)", "a bike", "a person walking" - never the
 * internal vehicle code (like "U7-482913"), which means nothing to anyone
 * on the road.
 */
public final class VehicleLabels {

    private VehicleLabels() {
    }

    /** "a car (AP 16 BX 2231)" / "a bike" / "a person walking". */
    public static String describe(Vehicle v) {
        if (v == null) return "a vehicle";
        TravelMode mode = v.effectiveTravelMode();
        String plate = cleanPlate(v.getPlateNumber());
        return plate != null && !mode.onFoot() ? mode.withArticle() + " (" + plate + ")" : mode.withArticle();
    }

    /** The same, starting with a capital letter - for the start of a sentence. */
    public static String describeCapitalised(Vehicle v) {
        return capitalise(describe(v));
    }

    public static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * Tidies a number plate as typed: upper case, single spaces, only
     * letters, digits, spaces and dashes, at most 15 characters. Null when
     * nothing usable is left.
     */
    public static String cleanPlate(String raw) {
        if (raw == null) return null;
        String s = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 -]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 15) s = s.substring(0, 15).trim();
        return s.length() < 2 ? null : s;
    }

    /** "Ravi" from "Ravi Kumar" - the only part of a name shown to other people. */
    public static String firstName(String fullName) {
        if (fullName == null) return null;
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) return null;
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
