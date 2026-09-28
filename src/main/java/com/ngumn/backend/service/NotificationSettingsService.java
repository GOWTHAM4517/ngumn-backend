package com.ngumn.backend.service;

import com.ngumn.backend.dto.NotificationSettings;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.util.NotificationPrefs;
import org.springframework.stereotype.Service;

/**
 * Each person's choice of what to be told about in the area around them
 * (see NotificationPrefs for how the warning services use it).
 */
@Service
public class NotificationSettingsService {

    private final UserRepository userRepository;

    public NotificationSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public NotificationSettings get(User user) {
        return NotificationSettings.of(user);
    }

    /** Saves whatever is set in `change`; the rest stays as it was. */
    public NotificationSettings update(User user, NotificationSettings change) {
        // Work on the stored row so the change sticks whichever copy of the
        // user the caller holds.
        User row = userRepository.findById(user.getId()).orElse(user);
        if (change != null) {
            if (change.getRadiusMeters() != null) row.setAlertRadiusM(NotificationPrefs.clamp(change.getRadiusMeters()));
            if (change.getHazards() != null) row.setNotifyHazards(change.getHazards());
            if (change.getRuleBreakers() != null) row.setNotifyRuleBreakers(change.getRuleBreakers());
        }
        row = userRepository.save(row);
        return NotificationSettings.of(row);
    }
}
