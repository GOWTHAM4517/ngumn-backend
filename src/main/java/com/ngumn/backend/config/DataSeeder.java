package com.ngumn.backend.config;

import com.ngumn.backend.entity.Role;
import com.ngumn.backend.entity.RoadSpeedLimit;
import com.ngumn.backend.entity.RuleZoneType;
import com.ngumn.backend.entity.TrafficRuleZone;
import com.ngumn.backend.entity.User;
import com.ngumn.backend.repository.RoadSpeedLimitRepository;
import com.ngumn.backend.repository.TrafficRuleZoneRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the admin login on a fresh database. Nothing else: road rules come
 * from OpenStreetMap (RoadDataService) and speed / no-entry zones are added
 * by the admin for real places. Safe to run repeatedly (checks existence
 * first).
 *
 * Default admin login (CHANGE via ADMIN_SEED_EMAIL / ADMIN_SEED_PASSWORD
 * for anything beyond local prototype use - see application.properties -
 * a public deployment must not keep the well-known default password):
 *   email:    admin@ngumn.local
 *   password: admin123
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String DEFAULT_PASSWORD = "admin123";

    private final UserRepository userRepository;
    private final RoadSpeedLimitRepository roadSpeedLimitRepository;
    private final TrafficRuleZoneRepository trafficRuleZoneRepository;

    @Value("${ngumn.admin.seed-email:admin@ngumn.local}")
    private String adminEmail;

    @Value("${ngumn.admin.seed-password:admin123}")
    private String adminPassword;

    @Value("${ngumn.admin.seed-name:Raksio Admin}")
    private String adminName;

    /**
     * ADMIN_RESET=true: on start-up, set the seeded admin's password to
     * ADMIN_SEED_PASSWORD again (and make sure it's an active admin) - the way
     * back in if the admin password is forgotten. Turn it off again afterwards.
     */
    @Value("${ngumn.admin.reset:false}")
    private boolean adminReset;

    public DataSeeder(UserRepository userRepository, RoadSpeedLimitRepository roadSpeedLimitRepository,
                      TrafficRuleZoneRepository trafficRuleZoneRepository) {
        this.userRepository = userRepository;
        this.roadSpeedLimitRepository = roadSpeedLimitRepository;
        this.trafficRuleZoneRepository = trafficRuleZoneRepository;
    }

    @Override
    public void run(String... args) {
        var existing = userRepository.findByEmail(adminEmail.toLowerCase());
        if (existing.isPresent() && adminReset) {
            User admin = existing.get();
            String salt = PasswordUtil.generateSalt();
            admin.setPasswordSalt(salt);
            admin.setPasswordHash(PasswordUtil.hash(adminPassword, salt));
            admin.setRole(Role.ADMIN);
            admin.setActive(true);
            userRepository.save(admin);
            log.warn("ADMIN_RESET=true: password of admin '{}' was reset. Remove ADMIN_RESET now.", adminEmail);
        }
        if (existing.isEmpty()) {
            String salt = PasswordUtil.generateSalt();
            User admin = User.builder()
                    .name(adminName)
                    .email(adminEmail.toLowerCase())
                    .passwordHash(PasswordUtil.hash(adminPassword, salt))
                    .passwordSalt(salt)
                    .role(Role.ADMIN)
                    .rewardPoints(0)
                    .active(true)
                    .build();
            userRepository.save(admin);
        }
        if (DEFAULT_PASSWORD.equals(adminPassword)) {
            log.warn("Admin account '{}' is using the default password. Set ADMIN_SEED_PASSWORD before exposing this " +
                    "server on the public internet.", adminEmail);
        }
        // Speed limits and one-way roads now come from the real map (RoadDataService);
        // nothing else is seeded.
    }
}
