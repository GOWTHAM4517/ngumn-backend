package com.ngumn.backend.service;

import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.dto.RuleZoneResponse;
import com.ngumn.backend.entity.TrafficViolation;
import com.ngumn.backend.entity.Vehicle;
import com.ngumn.backend.entity.VehicleType;
import com.ngumn.backend.entity.ViolationSource;
import com.ngumn.backend.entity.ViolationType;
import com.ngumn.backend.util.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic traffic-rule monitoring for every vehicle that shares its
 * location. Runs on each location update (see VehicleService) and checks:
 *
 * 1. Over-speeding - faster than a mapped speed zone's limit plus a small
 *    GPS tolerance (ngumn.rules.overspeed-tolerance-kmh, default 5).
 * 2. Wrong way - moving against the permitted direction of a one-way road
 *    (heading more than 120 degrees off the road's direction).
 * 3. No entry - moving inside a no-entry zone.
 * 4. Erratic driving - braking or speeding up sharply (15+ km/h per
 *    second) three times within a minute.
 *
 * A rule only counts as broken on the second update in a row that breaks
 * it (within 20 s), so one noisy GPS fix never creates a violation. The
 * driver is alerted through the normal alert feed; TrafficRuleService
 * collapses repeats within two minutes.
 *
 * Other drivers are protected too: whenever a vehicle is caught speeding,
 * going the wrong way or driving erratically, NearbyDangerService warns
 * the NGUMN drivers within 700 m ("Speeding vehicle nearby ... behind you
 * and approaching").
 *
 * Speed limits only apply inside mapped zones - elsewhere the app's Drive
 * Guard gives reminders but nothing is recorded, because the real limit
 * isn't known. Outside zones, a vehicle going faster than
 * ngumn.rules.nearby-warning-speed-kmh (default 80) still triggers the
 * warning to drivers nearby, without a violation. Erratic driving is
 * never recorded either (GPS speed alone can't prove it) - the driver
 * gets a caution and drivers nearby a warning. Demo Mode vehicles wander
 * randomly, so only their speed is checked, and they never cause warnings
 * to real drivers. Emergency vehicles on an active emergency run are
 * exempt, and so is anyone travelling on foot (travel mode WALK).
 */
@Service
public class RuleMonitorService {

    private static final Logger log = LoggerFactory.getLogger(RuleMonitorService.class);

    private static final double MOVING_KMH = 8.0;
    private static final double WRONG_WAY_ANGLE = 120.0;
    private static final long SUSTAIN_MS = 20_000;
    /** Erratic driving: a speed change of at least this many km/h per second (about 0.4 g)... */
    private static final double HARSH_KMH_PER_SECOND = 15.0;
    /** ...this many times... */
    private static final int HARSH_CHANGES = 3;
    /** ...within this window. */
    private static final long HARSH_WINDOW_MS = 60_000;

    private final TrafficRuleService trafficRuleService;
    private final NearbyDangerService nearbyDangerService;
    private final Map<String, Long> firstBreach = new ConcurrentHashMap<>();
    /** Last speed the phone reported for each vehicle: {km/h, time ms}. */
    private final Map<Long, double[]> lastReportedSpeed = new ConcurrentHashMap<>();
    /** Times of recent sharp speed changes for each vehicle. */
    private final Map<Long, Deque<Long>> harshChanges = new ConcurrentHashMap<>();

    @Value("${ngumn.rules.overspeed-tolerance-kmh:5}")
    private double toleranceKmh;

    /**
     * Outside mapped speed zones the real limit isn't known, so nothing is
     * recorded - but drivers nearby are warned about a vehicle going
     * faster than this.
     */
    @Value("${ngumn.rules.nearby-warning-speed-kmh:80}")
    private double nearbyWarningSpeedKmh;

    public RuleMonitorService(TrafficRuleService trafficRuleService, NearbyDangerService nearbyDangerService) {
        this.trafficRuleService = trafficRuleService;
        this.nearbyDangerService = nearbyDangerService;
    }

    /**
     * Checks one location update. prevLat / prevLng / prevTime are where the
     * vehicle was before this update - used to work out its real direction
     * of travel (and speed, if the phone didn't report one).
     */
    public void check(Vehicle vehicle, LocationUpdateRequest update,
                      Double prevLat, Double prevLng, LocalDateTime prevTime) {
        try {
            inspect(vehicle, update, prevLat, prevLng, prevTime);
        } catch (RuntimeException e) {
            // A rule check must never break a location update.
            log.warn("Rule monitor skipped vehicle {}: {}", vehicle.getId(), e.getMessage());
        }
    }

    private void inspect(Vehicle vehicle, LocationUpdateRequest update,
                         Double prevLat, Double prevLng, LocalDateTime prevTime) {
        Double lat = vehicle.getCurrentLatitude();
        Double lng = vehicle.getCurrentLongitude();
        if (lat == null || lng == null || vehicle.getOwner() == null) {
            return;
        }
        Long vid = vehicle.getId();
        if (vehicle.getVehicleType() == VehicleType.EMERGENCY && Boolean.TRUE.equals(vehicle.getEmergencyStatus())) {
            clear(vid);
            return;
        }
        // Someone on foot isn't bound by speed limits, one-way roads or
        // no-entry zones for vehicles.
        if (vehicle.effectiveTravelMode().onFoot()) {
            clear(vid);
            return;
        }
        boolean simulated = Boolean.TRUE.equals(vehicle.getIsSimulated());
        long now = System.currentTimeMillis();

        double speed = update.getSpeedKmh() != null && update.getSpeedKmh() > 0 ? update.getSpeedKmh() : 0;
        Double heading = null;
        if (prevLat != null && prevLng != null && prevTime != null) {
            double moved = GeoUtil.distanceMeters(prevLat, prevLng, lat, lng);
            long dtMs = Duration.between(prevTime, LocalDateTime.now()).toMillis();
            if (moved >= 10 && dtMs > 0 && dtMs <= 30_000) {
                heading = GeoUtil.bearingDegrees(prevLat, prevLng, lat, lng);
                if (speed == 0) {
                    speed = moved / (dtMs / 1000.0) * 3.6;
                }
            }
        }
        if (heading == null && update.getDirectionDegrees() != null && speed >= 10) {
            heading = update.getDirectionDegrees();
        }

        List<RuleZoneResponse> zones = trafficRuleService.activeZones();

        // 1. Over-speeding in a mapped speed zone (the most specific zone wins).
        RuleZoneResponse speedZone = null;
        for (RuleZoneResponse z : zones) {
            if (!"SPEED_LIMIT".equals(z.getKind()) || z.getSpeedLimitKmh() == null) continue;
            if (TrafficRuleService.gapMeters(z, lat, lng) > 0) continue;
            if (speedZone == null || radius(z) < radius(speedZone)) speedZone = z;
        }
        boolean over = speedZone != null && speed > speedZone.getSpeedLimitKmh() + toleranceKmh;
        if (sustained(vid + ":speed", over, now) && speedZone != null) {
            trafficRuleService.record(TrafficViolation.builder()
                    .vehicle(vehicle)
                    .driver(vehicle.getOwner())
                    .type(ViolationType.OVERSPEED)
                    .source(ViolationSource.MONITOR)
                    .latitude(lat)
                    .longitude(lng)
                    .speedKmh(Math.round(speed * 10) / 10.0)
                    .limitKmh(speedZone.getSpeedLimitKmh())
                    .headingDegrees(heading)
                    .zoneName(speedZone.getName())
                    .simulated(simulated)
                    .build(), !simulated);
            nearbyDangerService.warnAboutViolation(vehicle, ViolationType.OVERSPEED, speed, heading);
        }
        // Very fast outside every mapped zone: not a recorded violation (the
        // real limit isn't known), but the drivers around it are warned.
        boolean veryFast = !simulated && speedZone == null && speed >= nearbyWarningSpeedKmh;
        if (sustained(vid + ":fast", veryFast, now)) {
            nearbyDangerService.warnAboutViolation(vehicle, ViolationType.OVERSPEED, speed, heading);
        }

        if (simulated) {
            return;
        }
        boolean moving = speed >= MOVING_KMH;

        // 2. Wrong way on a one-way road.
        RuleZoneResponse wrongWay = null;
        if (moving && heading != null) {
            for (RuleZoneResponse z : zones) {
                if (!"ONE_WAY".equals(z.getKind()) || z.getEndLatitude() == null || z.getEndLongitude() == null) continue;
                if (!TrafficRuleService.appliesTo(z, vehicle.getVehicleType())) continue;
                GeoUtil.SegmentHit hit = GeoUtil.projectOnSegment(lat, lng,
                        z.getLatitude(), z.getLongitude(), z.getEndLatitude(), z.getEndLongitude());
                if (hit.getT() < -0.05 || hit.getT() > 1.05 || hit.getDistanceMeters() > radius(z)) continue;
                double allowed = GeoUtil.bearingDegrees(z.getLatitude(), z.getLongitude(),
                        z.getEndLatitude(), z.getEndLongitude());
                if (GeoUtil.angleDifference(heading, allowed) >= WRONG_WAY_ANGLE) {
                    wrongWay = z;
                    break;
                }
            }
        }
        if (sustained(vid + ":wrongway", wrongWay != null, now) && wrongWay != null) {
            trafficRuleService.record(TrafficViolation.builder()
                    .vehicle(vehicle)
                    .driver(vehicle.getOwner())
                    .type(ViolationType.WRONG_WAY)
                    .source(ViolationSource.MONITOR)
                    .latitude(lat)
                    .longitude(lng)
                    .speedKmh(Math.round(speed * 10) / 10.0)
                    .headingDegrees(heading)
                    .zoneName(wrongWay.getName())
                    .simulated(false)
                    .build(), true);
            nearbyDangerService.warnAboutViolation(vehicle, ViolationType.WRONG_WAY, speed, heading);
        }

        // 3. Driving into a no-entry zone.
        RuleZoneResponse noEntry = null;
        if (moving) {
            for (RuleZoneResponse z : zones) {
                if (!"NO_ENTRY".equals(z.getKind())) continue;
                if (!TrafficRuleService.appliesTo(z, vehicle.getVehicleType())) continue;
                if (TrafficRuleService.gapMeters(z, lat, lng) == 0) {
                    noEntry = z;
                    break;
                }
            }
        }
        if (sustained(vid + ":noentry", noEntry != null, now) && noEntry != null) {
            trafficRuleService.record(TrafficViolation.builder()
                    .vehicle(vehicle)
                    .driver(vehicle.getOwner())
                    .type(ViolationType.NO_ENTRY)
                    .source(ViolationSource.MONITOR)
                    .latitude(lat)
                    .longitude(lng)
                    .speedKmh(Math.round(speed * 10) / 10.0)
                    .headingDegrees(heading)
                    .zoneName(noEntry.getName())
                    .simulated(false)
                    .build(), true);
        }

        // 4. Erratic driving - repeated sharp braking / acceleration. Uses
        //    the speed the phone sent, never one worked out here from
        //    position changes (a GPS jump can fake those).
        if (erratic(vid, update.getSpeedKmh(), now)) {
            nearbyDangerService.cautionErraticDriver(vehicle);
            nearbyDangerService.warnAboutViolation(vehicle, ViolationType.DANGEROUS_DRIVING, speed, heading);
        }
    }

    /**
     * True from the second consecutive update that breaks a rule (within
     * 20 s of the first), so a single jumpy GPS fix never counts.
     */
    private boolean sustained(String key, boolean breaking, long now) {
        if (!breaking) {
            firstBreach.remove(key);
            return false;
        }
        Long first = firstBreach.get(key);
        if (first != null && now - first <= SUSTAIN_MS) {
            return true;
        }
        firstBreach.put(key, now);
        return false;
    }

    /**
     * True when this update is the third (or later) sharp speed change -
     * 15+ km/h per second, at 25 km/h or more - within a minute. Updates
     * closer than 1.5 s or further than 10 s apart aren't compared.
     */
    boolean erratic(Long vehicleId, Double reportedKmh, long now) {
        if (reportedKmh == null || reportedKmh < 0) {
            lastReportedSpeed.remove(vehicleId);
            return false;
        }
        double[] prev = lastReportedSpeed.put(vehicleId, new double[]{reportedKmh, now});
        if (prev == null) {
            return false;
        }
        double seconds = (now - prev[1]) / 1000.0;
        if (seconds < 1.5 || seconds > 10) {
            return false;
        }
        boolean sharp = Math.max(reportedKmh, prev[0]) >= 25
                && Math.abs(reportedKmh - prev[0]) / seconds >= HARSH_KMH_PER_SECOND;
        Deque<Long> times = harshChanges.computeIfAbsent(vehicleId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > HARSH_WINDOW_MS) {
                times.pollFirst();
            }
            if (sharp) {
                times.addLast(now);
            }
            return sharp && times.size() >= HARSH_CHANGES;
        }
    }

    private void clear(Long vehicleId) {
        String prefix = vehicleId + ":";
        firstBreach.keySet().removeIf(k -> k.startsWith(prefix));
        lastReportedSpeed.remove(vehicleId);
        harshChanges.remove(vehicleId);
    }

    private static double radius(RuleZoneResponse z) {
        return z.getRadiusMeters() != null ? z.getRadiusMeters() : 0;
    }
}
