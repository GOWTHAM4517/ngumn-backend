package com.ngumn.backend.live;

import com.ngumn.backend.entity.RoadReport;
import com.ngumn.backend.entity.RoadSpeedLimit;
import com.ngumn.backend.entity.TrafficRuleZone;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleStatus;
import com.ngumn.backend.repository.RoadReportRepository;
import com.ngumn.backend.repository.RoadSpeedLimitRepository;
import com.ngumn.backend.repository.TrafficRuleZoneRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Demo Mode is gone: every rule, report and vehicle Raksio works with is
 * real. Databases from before still hold the old sample data, so on every
 * start this removes it - the Hyderabad demo speed / one-way / no-entry
 * zones, the simulated vehicles (taken off the road, kept only for old
 * records) and the sample reports of the demo account (ended). Safe to run
 * again and again; it finds nothing once clean.
 */
@Component
public class LegacyDemoCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacyDemoCleanup.class);

    /** The account Demo Mode used for its sample reports. */
    public static final String DEMO_USER_EMAIL = "demo@ngumn.local";

    private final RoadSpeedLimitRepository speedLimits;
    private final TrafficRuleZoneRepository zones;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final RoadReportRepository reports;

    public LegacyDemoCleanup(RoadSpeedLimitRepository speedLimits, TrafficRuleZoneRepository zones,
                             VehicleRepository vehicles, UserRepository users, RoadReportRepository reports) {
        this.speedLimits = speedLimits;
        this.zones = zones;
        this.vehicles = vehicles;
        this.users = users;
        this.reports = reports;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            int n = clean();
            if (n > 0) log.info("Removed {} pieces of old Demo Mode data", n);
        } catch (RuntimeException e) {
            log.warn("Demo data clean-up skipped: {}", e.getMessage());
        }
    }

    /** Returns how many rows it changed. */
    public int clean() {
        int n = 0;
        for (RoadSpeedLimit s : speedLimits.findAll()) {
            if (s.getRoadName() != null && s.getRoadName().startsWith("Demo Zone")) {
                speedLimits.delete(s);
                n++;
            }
        }
        for (TrafficRuleZone z : zones.findAll()) {
            if (z.getName() != null && z.getName().startsWith("Demo ")) {
                zones.delete(z);
                n++;
            }
        }
        for (Vehicle v : vehicles.findByStatus(VehicleStatus.ACTIVE)) {
            if (Boolean.TRUE.equals(v.getIsSimulated())) {
                v.setStatus(VehicleStatus.INACTIVE);
                vehicles.save(v);
                n++;
            }
        }
        var demo = users.findByEmail(DEMO_USER_EMAIL).orElse(null);
        if (demo != null) {
            LocalDateTime now = LocalDateTime.now();
            for (RoadReport r : reports.findByReporterIdOrderByTimestampDesc(demo.getId())) {
                if (r.getExpiresAt() == null || r.getExpiresAt().isAfter(now)) {
                    r.setExpiresAt(now);
                    if (r.getClearedAt() == null) r.setClearedAt(now);
                    reports.save(r);
                    n++;
                }
            }
            if (Boolean.TRUE.equals(demo.getActive())) {
                demo.setActive(false);
                users.save(demo);
                n++;
            }
        }
        return n;
    }
}
