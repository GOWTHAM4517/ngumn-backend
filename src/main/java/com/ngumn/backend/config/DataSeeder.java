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
 * Seeds a default admin login plus demo traffic-rule zones (two speed
 * zones, a one-way street and a no-entry area) in the Demo Mode area, so
 * the app is immediately usable after a fresh `mvn spring-boot:run`,
 * without needing manual SQL. Safe to run repeatedly (checks existence
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

    public DataSeeder(UserRepository userRepository, RoadSpeedLimitRepository roadSpeedLimitRepository,
                      TrafficRuleZoneRepository trafficRuleZoneRepository) {
        this.userRepository = userRepository;
        this.roadSpeedLimitRepository = roadSpeedLimitRepository;
        this.trafficRuleZoneRepository = trafficRuleZoneRepository;
    }

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail.toLowerCase()).isEmpty()) {
            String salt = PasswordUtil.generateSalt();
            User admin = User.builder()
                    .name("NGUMN Admin")
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

        if (roadSpeedLimitRepository.count() == 0) {
            roadSpeedLimitRepository.save(RoadSpeedLimit.builder()
                    .roadName("Demo Zone A (default)")
                    .latitude(17.3850).longitude(78.4867)
                    .speedLimitKmh(50).radiusMeters(2000.0).build());
            roadSpeedLimitRepository.save(RoadSpeedLimit.builder()
                    .roadName("Demo Zone B - school zone (lower limit)")
                    .latitude(17.3900).longitude(78.4900)
                    .speedLimitKmh(30).radiusMeters(400.0).build());
        }

        // Demo one-way street and no-entry area in the Demo Mode area
        // (Hyderabad). Add real ones near you from the app: Road rules >
        // Rule zones (admin account).
        if (!trafficRuleZoneRepository.existsByName("Demo one-way street (eastbound)")) {
            trafficRuleZoneRepository.save(TrafficRuleZone.builder()
                    .name("Demo one-way street (eastbound)")
                    .zoneType(RuleZoneType.ONE_WAY)
                    .latitude(17.3868).longitude(78.4815)
                    .endLatitude(17.3868).endLongitude(78.4885)
                    .radiusMeters(25.0)
                    .active(true)
                    .build());
        }
        if (!trafficRuleZoneRepository.existsByName("Demo no-entry zone (market street)")) {
            trafficRuleZoneRepository.save(TrafficRuleZone.builder()
                    .name("Demo no-entry zone (market street)")
                    .zoneType(RuleZoneType.NO_ENTRY)
                    .latitude(17.3829).longitude(78.4892)
                    .radiusMeters(120.0)
                    .active(true)
                    .build());
        }
    }
}
