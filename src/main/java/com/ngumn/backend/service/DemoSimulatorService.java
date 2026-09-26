package com.ngumn.backend.service;

import com.ngumn.backend.dto.EmergencyStartRequest;
import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.dto.RoadReportRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.security.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DEMO MODE
 * ---------
 * Simulates multiple moving vehicles, hazards and an emergency-vehicle
 * scenario entirely in software, so the full NGUMN flow can be shown
 * without physical vehicles, RSUs, or ESP32 hardware.
 *
 * Every vehicle/report created here has isSimulated=true so simulated
 * data is never confused with real sensor data anywhere downstream
 * (mobile app, dashboard, database).
 */
@Service
public class DemoSimulatorService {

    private static final String DEMO_USER_EMAIL = "demo@ngumn.local";
    // Default demo area (Hyderabad, India) - change freely, this is just
    // a starting point for the simulated vehicles to wander around.
    private static final double BASE_LAT = 17.3850;
    private static final double BASE_LNG = 78.4867;
    // Simulated people who answer "is this true?" for pending reports and
    // rule-breaker complaints while the demo runs, so community
    // verification can be shown with one phone.
    private static final String[][] DEMO_VOTERS = {
            {"demo-voter-1@ngumn.local", "Asha (demo voter)"},
            {"demo-voter-2@ngumn.local", "Ravi (demo voter)"},
            {"demo-voter-3@ngumn.local", "Meena (demo voter)"},
            {"demo-voter-4@ngumn.local", "Kiran (demo voter)"},
    };

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final VehicleService vehicleService;
    private final RoadReportService roadReportService;
    private final EmergencyService emergencyService;
    private final CommunityService communityService;

    @Value("${ngumn.demo-mode.enabled:true}")
    private boolean demoModeEnabled;

    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Long> simulatedVehicleIds = new ArrayList<>();
    private final List<User> demoVoters = new ArrayList<>();

    public DemoSimulatorService(VehicleRepository vehicleRepository, UserRepository userRepository,
                                 VehicleService vehicleService, RoadReportService roadReportService,
                                 EmergencyService emergencyService, CommunityService communityService) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.vehicleService = vehicleService;
        this.roadReportService = roadReportService;
        this.emergencyService = emergencyService;
        this.communityService = communityService;
    }

    public synchronized String start(int vehicleCount) {
        if (!demoModeEnabled) {
            return "Demo mode is disabled (ngumn.demo-mode.enabled=false)";
        }
        User demoUser = ensureDemoUser();
        seedVehicles(demoUser, vehicleCount);
        ensureDemoVoters();
        running.set(true);
        return "Demo started with " + simulatedVehicleIds.size() + " simulated vehicles";
    }

    public synchronized void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int simulatedVehicleCount() {
        return simulatedVehicleIds.size();
    }

    private User ensureDemoUser() {
        return userRepository.findByEmail(DEMO_USER_EMAIL).orElseGet(() -> {
            String salt = PasswordUtil.generateSalt();
            User user = User.builder()
                    .name("NGUMN Demo Fleet")
                    .email(DEMO_USER_EMAIL)
                    .passwordHash(PasswordUtil.hash("demo-not-a-real-login", salt))
                    .passwordSalt(salt)
                    .role(Role.DRIVER)
                    .rewardPoints(0)
                    .active(true)
                    .build();
            return userRepository.save(user);
        });
    }

    private void ensureDemoVoters() {
        demoVoters.clear();
        for (String[] voter : DEMO_VOTERS) {
            User user = userRepository.findByEmail(voter[0]).orElseGet(() -> {
                String salt = PasswordUtil.generateSalt();
                return userRepository.save(User.builder()
                        .name(voter[1])
                        .email(voter[0])
                        .passwordHash(PasswordUtil.hash("demo-not-a-real-login", salt))
                        .passwordSalt(salt)
                        .role(Role.DRIVER)
                        .rewardPoints(0)
                        .active(true)
                        .build());
            });
            demoVoters.add(user);
        }
    }

    private void seedVehicles(User demoUser, int count) {
        simulatedVehicleIds.clear();
        VehicleType[] types = VehicleType.values();
        for (int i = 0; i < count; i++) {
            String code = "DEMO-" + (1000 + i);
            Vehicle vehicle = vehicleRepository.findByVehicleCode(code).orElseGet(() -> Vehicle.builder()
                    .vehicleCode(code)
                    .owner(demoUser)
                    .vehicleType(types[random.nextInt(types.length)])
                    .status(VehicleStatus.ACTIVE)
                    .emergencyStatus(false)
                    .isSimulated(true)
                    .speedKmh(0.0)
                    .directionDegrees(0.0)
                    .currentLatitude(BASE_LAT + (random.nextDouble() - 0.5) * 0.02)
                    .currentLongitude(BASE_LNG + (random.nextDouble() - 0.5) * 0.02)
                    .build());
            vehicle.setIsSimulated(true);
            vehicle = vehicleRepository.save(vehicle);
            simulatedVehicleIds.add(vehicle.getId());
        }
    }

    /** Moves every simulated vehicle a small random step and re-runs the
     *  risk engine and rule monitor for it, every 5 seconds while demo
     *  mode is running - and lets the demo voters answer open reports. */
    @Scheduled(fixedRate = 5000)
    public void tick() {
        if (!running.get() || simulatedVehicleIds.isEmpty()) {
            return;
        }
        for (Long id : simulatedVehicleIds) {
            vehicleRepository.findById(id).ifPresent(vehicle -> {
                double lat = (vehicle.getCurrentLatitude() != null ? vehicle.getCurrentLatitude() : BASE_LAT)
                        + (random.nextDouble() - 0.5) * 0.0015;
                double lng = (vehicle.getCurrentLongitude() != null ? vehicle.getCurrentLongitude() : BASE_LNG)
                        + (random.nextDouble() - 0.5) * 0.0015;
                // Occasionally simulate a speeding vehicle (demo only).
                double speed = Boolean.TRUE.equals(vehicle.getEmergencyStatus())
                        ? 70 + random.nextInt(20)
                        : random.nextInt(100) < 15
                            ? 75 + random.nextInt(25)
                            : 20 + random.nextInt(45);

                LocationUpdateRequest update = new LocationUpdateRequest();
                update.setLatitude(lat);
                update.setLongitude(lng);
                update.setSpeedKmh((double) speed);
                update.setDirectionDegrees((double) random.nextInt(360));
                vehicleService.updateLocation(vehicle, update);
            });
        }

        // Now and then a simulated person answers an open report or
        // complaint (about one answer every 12 s).
        if (random.nextDouble() < 0.4) {
            communityService.demoVote(demoVoters, random);
        }
    }

    public void triggerHazard() {
        if (simulatedVehicleIds.isEmpty()) {
            return;
        }
        Long id = simulatedVehicleIds.get(random.nextInt(simulatedVehicleIds.size()));
        vehicleRepository.findById(id).ifPresent(vehicle -> {
            if (vehicle.getCurrentLatitude() == null) return;
            RoadReportRequest request = new RoadReportRequest();
            ReportType[] types = {ReportType.POTHOLE, ReportType.ACCIDENT, ReportType.TRAFFIC_JAM, ReportType.ROAD_HAZARD};
            request.setType(types[random.nextInt(types.length)]);
            request.setDescription("(Simulated demo-mode hazard report)");
            request.setLatitude(vehicle.getCurrentLatitude());
            request.setLongitude(vehicle.getCurrentLongitude());
            roadReportService.submit(vehicle.getOwner(), request);
        });
    }

    public void triggerEmergency() {
        if (simulatedVehicleIds.isEmpty()) {
            return;
        }
        Long id = simulatedVehicleIds.get(random.nextInt(simulatedVehicleIds.size()));
        vehicleRepository.findById(id).ifPresent(vehicle -> {
            if (vehicle.getCurrentLatitude() == null) return;
            EmergencyStartRequest request = new EmergencyStartRequest();
            request.setVehicleId(vehicle.getId());
            request.setOriginLatitude(vehicle.getCurrentLatitude());
            request.setOriginLongitude(vehicle.getCurrentLongitude());
            request.setDestinationLatitude(vehicle.getCurrentLatitude() + 0.01);
            request.setDestinationLongitude(vehicle.getCurrentLongitude() + 0.01);
            emergencyService.start(request);
        });
    }
}
