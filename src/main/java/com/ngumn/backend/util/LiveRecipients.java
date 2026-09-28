package com.ngumn.backend.util;

import com.ngumn.backend.entity.Vehicle;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Who to tell about something happening on the road: one vehicle per
 * person - the one they were seen in most recently - so nobody hears the
 * same warning twice just because an older account has two vehicles.
 */
public final class LiveRecipients {

    private LiveRecipients() {
    }

    /** Each owner's most recently seen vehicle among those that pass `live`. */
    public static Collection<Vehicle> latestPerOwner(Iterable<Vehicle> vehicles, Predicate<Vehicle> live) {
        Map<Long, Vehicle> byOwner = new LinkedHashMap<>();
        for (Vehicle v : vehicles) {
            if (v == null || v.getOwner() == null || v.getOwner().getId() == null || !live.test(v)) continue;
            byOwner.merge(v.getOwner().getId(), v, (a, b) -> seen(b).isAfter(seen(a)) ? b : a);
        }
        return new ArrayList<>(byOwner.values());
    }

    private static LocalDateTime seen(Vehicle v) {
        return v.getLastLocationUpdate() != null ? v.getLastLocationUpdate() : LocalDateTime.MIN;
    }
}
