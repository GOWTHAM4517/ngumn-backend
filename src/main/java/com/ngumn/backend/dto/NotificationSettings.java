package com.ngumn.backend.dto;

import com.ngumn.backend.entity.User;
import com.ngumn.backend.util.NotificationPrefs;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Settings > Notifications in the app: how far around you to hear about
 * reports and rule-breakers, and which of them. In a change, anything
 * left out (null) stays as it is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSettings {

    /** 200 m to 10 km (the app offers 500 m, 1 km, 2 km and 5 km). */
    private Integer radiusMeters;
    /** Hazards someone reports near you. */
    private Boolean hazards;
    /** Rule-breakers near you - caught by the server or reported by people. */
    private Boolean ruleBreakers;

    public static NotificationSettings of(User u) {
        return new NotificationSettings(NotificationPrefs.radiusMeters(u), NotificationPrefs.hazards(u),
                NotificationPrefs.ruleBreakers(u));
    }
}
