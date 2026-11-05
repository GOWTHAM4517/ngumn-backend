package com.ngumn.backend.service;

import com.ngumn.backend.dto.CarPointRequest;
import com.ngumn.backend.dto.CarRequest;
import com.ngumn.backend.dto.CarTransferRequest;
import com.ngumn.backend.dto.IncidentRequest;
import com.ngumn.backend.dto.IncidentResponse;
import com.ngumn.backend.dto.TestDriveRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.util.LiveRecipients;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Car guard: watching a car without a phone in it.
 *
 * <pre>
 *   With no device in the car at all
 *     Report stolen -> police request (VEHICLE_THEFT) with the car's details
 *                   -> Raksio users within 5 km: "Stolen car ... tap if you see it"
 *     "I saw it"    -> owner + the police unit on the case, request moves there
 *     Plate watch   -> a rule-breaker report naming its number plate counts as a sighting
 *
 *   With a small GPS tracker (not a phone) sending to /api/tracker
 *     Guard on / at night (22:00-06:00): moving or engine started -> owner alerted
 *       (not when the owner's own Raksio phone is travelling with it)
 *     Speed alerts over the owner's limit (a driver, a learner)
 *     Tracker went quiet while moving / stolen / on a test drive -> owner alerted
 *     Tracker alarms (power cut, tampering, SOS button) -> owner alerted
 *     Stolen: the police request follows the car; live page /car.html?t=...
 *     Test drives (showrooms): customer, time allowed, area allowed;
 *       over the limit, left the area, late back -> staff alerted;
 *       back at the showroom and parked -> ended by itself with a report
 *     Trail and trips for 30 days; handing a sold car over to the buyer's account
 * </pre>
 *
 * Messages start with a fixed headline (HEADLINES) - the app uses it as the
 * notification title (src/lib/alert-text.ts).
 */
@Service
public class CarGuardService {

    public static final String CAR_GUARD = "Car guard";
    public static final String TEST_DRIVE = "Test drive";
    public static final String STOLEN_CAR = "Stolen car";
    public static final String STOLEN_SEEN = "Stolen car seen";
    public static final String CAR_FOUND = "Car found";
    public static final List<String> HEADLINES = List.of(CAR_GUARD, TEST_DRIVE, STOLEN_CAR, STOLEN_SEEN, CAR_FOUND);

    static final int MAX_CARS = 50;
    /** Faster than this is "moving" (GPS speed of a parked car wanders 0-5 km/h). */
    static final double MOVING_KMH = 10;
    /** Further than this from where it was parked is "moved" (GPS drift stays well inside). */
    static final double MOVED_M = 200;
    /** Within this of the same spot is "standing still"; after PARK_AFTER that spot is where it's parked. */
    static final double STILL_M = 50;
    static final Duration PARK_AFTER = Duration.ofMinutes(2);
    static final Duration MOVE_ALERT_GAP = Duration.ofMinutes(10);
    static final Duration SPEED_ALERT_GAP = Duration.ofMinutes(5);
    static final Duration ALARM_GAP = Duration.ofMinutes(10);
    /** Quiet this long while moving / stolen / on a test drive: tell the owner. */
    static final Duration SILENT_MOVING = Duration.ofMinutes(10);
    /** Quiet this long while parked with the guard on (trackers report less often when parked). */
    static final Duration SILENT_PARKED = Duration.ofMinutes(30);
    static final Duration ONLINE = Duration.ofMinutes(5);
    /** Alerts only for positions this recent (a tracker sends what it saved while it had no signal later). */
    static final Duration FRESH = Duration.ofMinutes(5);
    static final Duration BROADCAST_GAP = Duration.ofMinutes(10);
    static final Duration PER_PERSON_GAP = Duration.ofMinutes(30);
    static final Duration SIGHTING_GAP = Duration.ofMinutes(5);
    /** The owner's phone this close to the car and seen this recently: the owner is in it. */
    static final double ABOARD_M = 150;
    static final long ABOARD_SECONDS = 120;
    static final double NEARBY_M = 5000;
    static final long LIVE_SECONDS = 15 * 60;
    static final int KEEP_DAYS = 30;
    static final int SIGHTING_POINTS = 25;
    static final int MAX_TRAIL_POINTS = 1500;

    private static final Logger log = LoggerFactory.getLogger(CarGuardService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GuardedCarRepository cars;
    private final CarPositionRepository positions;
    private final TestDriveRepository drives;
    private final CarSightingRepository sightings;
    private final ViolationComplaintRepository complaints;
    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final AlertService alerts;
    private final IncidentService incidents;
    private final RewardService rewards;
    private final NgumnWebSocketHandler webSocket;

    @Value("${ngumn.public-url:}")
    private String publicUrl;

    /** "userId:carId" -> when they were last told about that stolen car. */
    private final Map<String, LocalDateTime> told = new ConcurrentHashMap<>();
    /** "carId:alarm" -> when the owner was last told about that tracker alarm. */
    private final Map<String, LocalDateTime> alarmed = new ConcurrentHashMap<>();
    /** Rule-breaker reports up to here have been checked against stolen cars' plates. */
    private volatile LocalDateTime plateCheckedUpTo = LocalDateTime.now().minusMinutes(2);

    public CarGuardService(GuardedCarRepository cars, CarPositionRepository positions, TestDriveRepository drives,
                           CarSightingRepository sightings, ViolationComplaintRepository complaints,
                           VehicleRepository vehicles, UserRepository users, AlertService alerts,
                           IncidentService incidents, RewardService rewards, NgumnWebSocketHandler webSocket) {
        this.cars = cars;
        this.positions = positions;
        this.drives = drives;
        this.sightings = sightings;
        this.complaints = complaints;
        this.vehicles = vehicles;
        this.users = users;
        this.alerts = alerts;
        this.incidents = incidents;
        this.rewards = rewards;
        this.webSocket = webSocket;
    }

    /** One position from a tracker. speedKmh / heading / ignition / battery / at / alarm may be missing. */
    public record Fix(String trackerId, double lat, double lon, Double speedKmh, Double heading,
                      Boolean ignition, Integer battery, LocalDateTime at, String alarm) {
    }

    // ------------------------------------------------------------------ the owner's cars

    public Map<String, Object> list(User owner, String requestBase) {
        List<GuardedCar> mine = cars.findByOwnerIdOrderByCreatedAtAsc(owner.getId());
        List<Map<String, Object>> views = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("total", "moving", "parked", "testDrive", "stolen", "offline", "noTracker")) counts.put(k, 0);
        for (GuardedCar c : mine) {
            Map<String, Object> v = view(c, requestBase);
            views.add(v);
            counts.merge("total", 1, Integer::sum);
            String state = (String) v.get("state");
            String key = switch (state) {
                case "MOVING" -> "moving";
                case "PARKED" -> "parked";
                case "TEST_DRIVE" -> "testDrive";
                case "STOLEN" -> "stolen";
                case "OFFLINE" -> "offline";
                default -> "noTracker";
            };
            counts.merge(key, 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cars", views);
        m.put("counts", counts);
        return m;
    }

    public Map<String, Object> get(User owner, Long carId, String requestBase) {
        return view(mine(owner, carId), requestBase);
    }

    @Transactional
    public Map<String, Object> add(User owner, CarRequest r, String requestBase) {
        if (cars.countByOwnerId(owner.getId()) >= MAX_CARS) {
            throw ApiException.badRequest("You can watch up to " + MAX_CARS + " cars.");
        }
        String name = firstText(r.getName(), r.getModel(), r.getPlate());
        if (name == null) throw ApiException.badRequest("Give the car a name, e.g. \"My Innova\".");
        GuardedCar car = GuardedCar.builder()
                .owner(owner)
                .name(name)
                .trackerId(r.getTrackerId() != null && !r.getTrackerId().isBlank() ? freeTrackerId(r.getTrackerId(), null) : uniqueTrackerId())
                .trackToken(uniqueToken())
                .status("NORMAL")
                .build();
        apply(car, r, owner);
        return view(cars.save(car), requestBase);
    }

    @Transactional
    public Map<String, Object> update(User owner, Long carId, CarRequest r, String requestBase) {
        GuardedCar car = mine(owner, carId);
        if (r.getName() != null) {
            String name = text(r.getName());
            if (name == null) throw ApiException.badRequest("The car needs a name.");
            car.setName(name);
        }
        if (r.getTrackerId() != null && !r.getTrackerId().isBlank() && !r.getTrackerId().trim().equals(car.getTrackerId())) {
            car.setTrackerId(freeTrackerId(r.getTrackerId(), car.getId()));
        }
        apply(car, r, owner);
        return view(cars.save(car), requestBase);
    }

    @Transactional
    public void delete(User owner, Long carId) {
        GuardedCar car = mine(owner, carId);
        if (car.stolen()) {
            throw ApiException.badRequest("It's reported stolen - mark it found first, so the police case is closed.");
        }
        drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").ifPresent(d -> finish(d, now()));
        positions.deleteByCarId(car.getId());
        cars.delete(car);
        changed(car);
    }

    /** Plate, model, colour, guard settings, home - whatever was sent. */
    private void apply(GuardedCar car, CarRequest r, User owner) {
        // The controller validates too (@Valid); this keeps the service safe on its own.
        range(r.getNightFrom(), 0, 23, "Night starts at an hour from 0 to 23.");
        range(r.getNightTo(), 0, 23, "Night ends at an hour from 0 to 23.");
        range(r.getSpeedLimitKmh(), 20, 200, "The speed limit is 20-200 km/h.");
        range(r.getHomeRadiusM(), 50, 5000, "The home area is 50 m to 5 km across.");
        if (r.getPlate() != null) {
            String key = plateKey(r.getPlate());
            if (key != null) {
                boolean twice = cars.findByOwnerIdOrderByCreatedAtAsc(owner.getId()).stream()
                        .anyMatch(o -> key.equals(o.getPlateKey()) && !Objects.equals(o.getId(), car.getId()));
                if (twice) throw ApiException.badRequest("You've already added " + r.getPlate().trim() + ".");
            }
            car.setPlate(key != null ? r.getPlate().trim().toUpperCase(Locale.ROOT) : null);
            car.setPlateKey(key);
        }
        if (r.getModel() != null) car.setModel(text(r.getModel()));
        if (r.getColour() != null) car.setColour(text(r.getColour()));
        if (r.getGuardOn() != null) car.setGuardOn(r.getGuardOn());
        if (r.getNightGuard() != null) car.setNightGuard(r.getNightGuard());
        if (r.getNightFrom() != null) car.setNightFrom(r.getNightFrom());
        if (r.getNightTo() != null) car.setNightTo(r.getNightTo());
        if (r.getSpeedAlerts() != null) car.setSpeedAlerts(r.getSpeedAlerts());
        if (r.getSpeedLimitKmh() != null) car.setSpeedLimitKmh(r.getSpeedLimitKmh());
        if (Boolean.TRUE.equals(r.getClearHome())) {
            car.setHomeLatitude(null);
            car.setHomeLongitude(null);
            car.setHomeRadiusM(null);
        } else if (r.getHomeLatitude() != null || r.getHomeLongitude() != null) {
            if (r.getHomeLatitude() == null || r.getHomeLongitude() == null || !validPoint(r.getHomeLatitude(), r.getHomeLongitude())) {
                throw ApiException.badRequest("That home location doesn't look right.");
            }
            car.setHomeLatitude(r.getHomeLatitude());
            car.setHomeLongitude(r.getHomeLongitude());
            car.setHomeRadiusM(r.getHomeRadiusM() != null ? r.getHomeRadiusM() : 150);
            // Parked there now: the guard measures from it straight away.
            car.setParkedLatitude(r.getHomeLatitude());
            car.setParkedLongitude(r.getHomeLongitude());
        } else if (r.getHomeRadiusM() != null && car.getHomeLatitude() != null) {
            car.setHomeRadiusM(r.getHomeRadiusM());
        }
    }

    // ------------------------------------------------------------------ positions from the tracker

    /**
     * A position from the car's tracker (or the owner's phone standing in for
     * one). Keeps the trail and runs the guard, speed, test-drive and stolen
     * rules. Unknown tracker ids are refused.
     */
    @Transactional
    public Map<String, Object> ingest(Fix f) {
        if (f.trackerId() == null || f.trackerId().isBlank()) {
            throw ApiException.badRequest("Send the tracker's device id (id=...).");
        }
        GuardedCar car = cars.findByTrackerId(f.trackerId().trim())
                .orElseThrow(() -> ApiException.notFound("No car uses this tracker id. Add the car in Raksio > Car guard first."));
        if (!validPoint(f.lat(), f.lon()) || (Math.abs(f.lat()) < 0.0001 && Math.abs(f.lon()) < 0.0001)) {
            throw ApiException.badRequest("That location doesn't look right.");
        }
        LocalDateTime now = now();
        LocalDateTime at = f.at() == null || f.at().isAfter(now.plusMinutes(5)) ? now : f.at();
        if (at.isBefore(now.minusDays(KEEP_DAYS))) return result(car, "too old");

        LocalDateTime last = car.getLastSeenAt();
        if (last != null && Math.abs(Duration.between(last, at).toMillis()) < 2000) return result(car, "too soon");
        boolean late = last != null && at.isBefore(last);

        double moved = car.getLastLatitude() != null
                ? GeoUtil.distanceMeters(car.getLastLatitude(), car.getLastLongitude(), f.lat(), f.lon()) : 0;
        long gapS = last != null ? Math.max(1, Duration.between(last, at).getSeconds()) : 0;
        Double speed = f.speedKmh();
        if (speed != null && (speed < 0 || speed > 300 || speed.isNaN())) speed = null;
        if (speed == null && !late && last != null && gapS <= 600) speed = Math.min(250, moved / gapS * 3.6);

        positions.save(CarPosition.builder().carId(car.getId()).latitude(f.lat()).longitude(f.lon())
                .speedKmh(speed).heading(f.heading()).ignition(f.ignition()).at(at).build());
        // Saved while it had no signal, arriving now: part of the trail, but it's not where the car is.
        if (late) return result(car, "added to the trail");

        boolean fresh = !at.isBefore(now.minus(FRESH));
        double lat = f.lat();
        double lon = f.lon();

        if (Boolean.TRUE.equals(car.getSilentAlerted())) {
            car.setSilentAlerted(false);
            if (fresh && car.stolen()) {
                tellOwner(car, RiskLevel.HIGH, CAR_GUARD + ": " + car.getName() + "'s tracker is sending again"
                        + where(car, lat, lon) + ".", lat, lon);
            }
        }

        // Standing still / where it's parked
        if (car.getStillLatitude() == null
                || GeoUtil.distanceMeters(car.getStillLatitude(), car.getStillLongitude(), lat, lon) > STILL_M) {
            car.setStillLatitude(lat);
            car.setStillLongitude(lon);
            car.setStillSince(at);
        } else if (car.getStillSince() != null && !Duration.between(car.getStillSince(), at).minus(PARK_AFTER).isNegative()) {
            car.setParkedLatitude(car.getStillLatitude());
            car.setParkedLongitude(car.getStillLongitude());
        }
        boolean parkedNow = car.getStillSince() != null && !Duration.between(car.getStillSince(), at).minus(PARK_AFTER).isNegative();

        TestDrive drive = drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").orElse(null);
        if (drive != null) testDriveFix(car, drive, lat, lon, speed, moved, gapS, fresh, parkedNow, at);

        boolean ownerAboard = ownerAboard(car, lat, lon, now);
        if (fresh && !car.stolen() && drive == null && !ownerAboard) {
            guardFix(car, f, lat, lon, speed, at, now);
            speedFix(car, lat, lon, speed, now);
        }
        if (fresh && f.alarm() != null && !f.alarm().isBlank()) alarm(car, f.alarm().trim(), lat, lon, now);

        if (car.stolen()) {
            // The first fix since the report, or it moved: the request follows the car.
            boolean firstFix = car.getLastSeenAt() == null || car.getStolenAt() == null || car.getLastSeenAt().isBefore(car.getStolenAt());
            if (car.getTheftIncidentId() != null && (firstFix || moved >= 30)) incidents.moveTo(car.getTheftIncidentId(), lat, lon);
            if (fresh && gapPassed(car.getLastBroadcastAt(), BROADCAST_GAP, now)) {
                car.setLastBroadcastAt(now);
                broadcastStolen(car, lat, lon);
                if (car.getTheftIncidentId() != null && speed != null && speed >= MOVING_KMH) {
                    incidents.tellAssignee(car.getTheftIncidentId(), STOLEN_CAR + ": the " + car.describe() + " is moving "
                            + compass(f.heading()) + "at " + Math.round(speed) + " km/h. Its live position is on the request.");
                }
            }
        }

        car.setLastLatitude(lat);
        car.setLastLongitude(lon);
        car.setLastSpeedKmh(speed);
        car.setLastHeading(f.heading());
        if (f.ignition() != null) car.setLastIgnition(f.ignition());
        if (f.battery() != null) car.setLastBattery(Math.max(0, Math.min(100, f.battery())));
        car.setLastSeenAt(at);
        cars.save(car);
        changed(car);
        return result(car, null);
    }

    /** Guard on, or night: the car moving or its engine starting is an alert. */
    private void guardFix(GuardedCar car, Fix f, double lat, double lon, Double speed, LocalDateTime at, LocalDateTime now) {
        boolean night = Boolean.TRUE.equals(car.getNightGuard()) && isNight(car, at);
        boolean armed = Boolean.TRUE.equals(car.getGuardOn()) || night;
        if (!armed) return;
        double fromParked = car.getParkedLatitude() != null
                ? GeoUtil.distanceMeters(car.getParkedLatitude(), car.getParkedLongitude(), lat, lon) : 0;
        boolean moving = (speed != null && speed >= MOVING_KMH) || fromParked > MOVED_M;
        boolean started = Boolean.TRUE.equals(f.ignition()) && Boolean.FALSE.equals(car.getLastIgnition());
        if (!moving && !started) return;
        if (!gapPassed(car.getLastMoveAlertAt(), MOVE_ALERT_GAP, now)) return;
        car.setLastMoveAlertAt(now);
        String what = moving
                ? "is moving" + (speed != null && speed >= 1 ? " (" + Math.round(speed) + " km/h)" : "")
                : "has been started";
        tellOwner(car, RiskLevel.HIGH, CAR_GUARD + ": your " + car.describe() + " " + what
                + where(car, lat, lon) + ", " + clock(at) + ". Not you? Open it and tap Report stolen.", lat, lon);
    }

    private void speedFix(GuardedCar car, double lat, double lon, Double speed, LocalDateTime now) {
        if (!Boolean.TRUE.equals(car.getSpeedAlerts()) || speed == null || speed <= limitOf(car)) return;
        if (!gapPassed(car.getLastSpeedAlertAt(), SPEED_ALERT_GAP, now)) return;
        car.setLastSpeedAlertAt(now);
        tellOwner(car, RiskLevel.MEDIUM, CAR_GUARD + ": " + car.getName() + " is doing " + Math.round(speed)
                + " km/h (your limit is " + limitOf(car) + ")" + where(car, lat, lon) + ".", lat, lon);
    }

    /** A tracker's own alarm (Traccar names): power cut, tampering, SOS button... */
    private void alarm(GuardedCar car, String alarm, double lat, double lon, LocalDateTime now) {
        String a = alarm.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        boolean armed = Boolean.TRUE.equals(car.getGuardOn()) || (Boolean.TRUE.equals(car.getNightGuard()) && isNight(car, now));
        String text = switch (a) {
            case "powercut", "poweroff" -> "its tracker lost power - someone may have cut its wires";
            case "tampering", "removing", "tamper" -> "someone is tampering with its tracker";
            case "sos" -> "the SOS button in the car was pressed";
            case "vibration", "shock", "movement" -> armed ? "it was shaken or hit while parked" : null;
            case "door" -> armed ? "a door was opened" : null;
            case "lowbattery", "lowpower" -> "its tracker's battery is low";
            case "accident", "hardbraking", "hardacceleration" -> "it may have braked hard or hit something";
            default -> "its tracker raised an alarm (" + alarm + ")";
        };
        if (text == null) return;
        String key = car.getId() + ":" + a;
        if (!gapPassed(alarmed.get(key), ALARM_GAP, now)) return;
        alarmed.put(key, now);
        RiskLevel level = a.startsWith("lowbattery") || a.startsWith("lowpower") ? RiskLevel.MEDIUM : RiskLevel.HIGH;
        tellOwner(car, level, CAR_GUARD + ": " + car.getName() + " - " + text + where(car, lat, lon) + ".", lat, lon);
    }

    /** The owner's own Raksio phone is right by the car and recently seen: it's them driving. */
    private boolean ownerAboard(GuardedCar car, double lat, double lon, LocalDateTime now) {
        for (Vehicle v : vehicles.findByOwnerId(car.getOwner().getId())) {
            if (Boolean.TRUE.equals(v.getIsSimulated()) || !v.seenWithin(now, ABOARD_SECONDS)) continue;
            if (GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lon) <= ABOARD_M) return true;
        }
        return false;
    }

    private Map<String, Object> result(GuardedCar car, String ignored) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("car", car.getId());
        if (ignored != null) m.put("note", ignored);
        return m;
    }

    // ------------------------------------------------------------------ test drives

    @Transactional
    public Map<String, Object> startTestDrive(User owner, Long carId, TestDriveRequest r) {
        GuardedCar car = mine(owner, carId);
        if (car.stolen()) throw ApiException.badRequest("This car is reported stolen.");
        if (text(r.getCustomerName()) == null) throw ApiException.badRequest("Who's driving? Type the customer's name.");
        range(r.getMinutes(), 10, 240, "A test drive is 10 minutes to 4 hours.");
        range(r.getRadiusM(), 1000, 50000, "The test-drive area is 1-50 km.");
        drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").ifPresent(d -> {
            throw ApiException.badRequest(car.getName() + " is already out with " + d.getCustomerName() + ". End that test drive first.");
        });
        Double cLat = car.getHomeLatitude() != null ? car.getHomeLatitude() : car.getLastLatitude();
        Double cLon = car.getHomeLatitude() != null ? car.getHomeLongitude() : car.getLastLongitude();
        if (cLat == null) {
            throw ApiException.badRequest("Set the car's home (the showroom) first, so Car guard knows where test drives start.");
        }
        String phone = r.getCustomerPhone() != null && !r.getCustomerPhone().isBlank()
                ? ContactService.normalise(r.getCustomerPhone()) : null;
        LocalDateTime now = now();
        TestDrive d = drives.save(TestDrive.builder()
                .carId(car.getId())
                .startedBy(owner)
                .customerName(r.getCustomerName().trim())
                .customerPhone(phone)
                .startedAt(now)
                .dueBackAt(now.plusMinutes(r.getMinutes() != null ? r.getMinutes() : 30))
                .centerLatitude(cLat)
                .centerLongitude(cLon)
                .radiusM(r.getRadiusM() != null ? r.getRadiusM() : 5000)
                .status("ACTIVE")
                .build());
        changed(car);
        return driveView(car, d);
    }

    @Transactional
    public Map<String, Object> endTestDrive(User owner, Long carId) {
        GuardedCar car = mine(owner, carId);
        TestDrive d = drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE")
                .orElseThrow(() -> ApiException.badRequest("No test drive is going on for this car."));
        finish(d, now());
        changed(car);
        return driveView(car, d);
    }

    public List<Map<String, Object>> testDrives(User owner, Long carId) {
        GuardedCar car = mine(owner, carId);
        // Only the drives this account ran - a car handed to a buyer doesn't show the showroom's customers.
        return drives.findTop20ByCarIdOrderByStartedAtDesc(car.getId()).stream()
                .filter(d -> d.getStartedBy() != null && d.getStartedBy().getId().equals(owner.getId()))
                .map(d -> driveView(car, d)).toList();
    }

    private void testDriveFix(GuardedCar car, TestDrive d, double lat, double lon, Double speed, double moved,
                              long gapS, boolean fresh, boolean parkedNow, LocalDateTime at) {
        if (car.getLastSeenAt() != null && !car.getLastSeenAt().isBefore(d.getStartedAt()) && gapS <= 600 && moved < 10_000) {
            d.setDistanceM(d.getDistanceM() + moved);
        }
        int limit = limitOf(car);
        if (speed != null && speed > d.getMaxSpeedKmh()) d.setMaxSpeedKmh(speed);
        if (speed != null && speed > limit && !Boolean.TRUE.equals(d.getOverLimitNow())) {
            d.setOverLimitNow(true);
            d.setOverLimitCount(d.getOverLimitCount() + 1);
            if (fresh) {
                tell(d.getStartedBy(), car, RiskLevel.MEDIUM, TEST_DRIVE + ": " + d.getCustomerName() + " is doing "
                        + Math.round(speed) + " km/h in " + car.getName() + " (limit " + limit + ").", lat, lon);
            }
        } else if (speed != null && speed < limit - 5) {
            d.setOverLimitNow(false); // counted again only after slowing down properly
        }
        double fromShowroom = GeoUtil.distanceMeters(d.getCenterLatitude(), d.getCenterLongitude(), lat, lon);
        if (fromShowroom > d.getRadiusM() && !Boolean.TRUE.equals(d.getLeftArea())) {
            d.setLeftArea(true);
            if (fresh) {
                tell(d.getStartedBy(), car, RiskLevel.HIGH, TEST_DRIVE + ": " + d.getCustomerName() + " has taken "
                        + car.getName() + " " + km(fromShowroom) + " from the showroom (allowed " + km(d.getRadiusM()) + ").", lat, lon);
            }
        }
        // Back at the showroom and parked after a real drive: ends by itself.
        boolean back = fromShowroom <= Math.max(150, car.getHomeRadiusM() != null ? car.getHomeRadiusM() : 0);
        if (back && parkedNow && d.getDistanceM() >= 500) {
            finish(d, at);
            tell(d.getStartedBy(), car, RiskLevel.LOW, TEST_DRIVE + ": " + d.getCustomerName() + " is back with "
                    + car.getName() + ". " + driveSummary(car, d), lat, lon);
            return;
        }
        drives.save(d);
    }

    private void finish(TestDrive d, LocalDateTime at) {
        d.setStatus("ENDED");
        d.setEndedAt(at);
        d.setReturnedLate(at.isAfter(d.getDueBackAt()));
        drives.save(d);
    }

    Map<String, Object> driveView(GuardedCar car, TestDrive d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("customerName", d.getCustomerName());
        m.put("customerPhone", d.getCustomerPhone());
        m.put("startedAt", d.getStartedAt());
        m.put("dueBackAt", d.getDueBackAt());
        m.put("endedAt", d.getEndedAt());
        m.put("status", d.getStatus());
        m.put("radiusM", d.getRadiusM());
        m.put("distanceKm", Math.round(d.getDistanceM() / 100.0) / 10.0);
        m.put("maxSpeedKmh", (int) Math.round(d.getMaxSpeedKmh()));
        m.put("speedLimitKmh", limitOf(car));
        m.put("overLimitCount", d.getOverLimitCount());
        m.put("leftArea", d.getLeftArea());
        m.put("late", d.active() ? now().isAfter(d.getDueBackAt()) : Boolean.TRUE.equals(d.getReturnedLate()));
        LocalDateTime end = d.getEndedAt() != null ? d.getEndedAt() : now();
        m.put("minutes", Math.max(0, Duration.between(d.getStartedAt(), end).toMinutes()));
        if (d.active()) m.put("minutesLeft", Duration.between(now(), d.getDueBackAt()).toMinutes());
        m.put("verdict", verdict(car, d));
        m.put("summary", driveSummary(car, d));
        return m;
    }

    /** CAREFUL (nothing to note), CHECK (a little fast, or late), RISKY (often fast, far over, or left the area). */
    static String verdict(GuardedCar car, TestDrive d) {
        int limit = car.getSpeedLimitKmh() != null ? car.getSpeedLimitKmh() : 80;
        boolean late = Boolean.TRUE.equals(d.getReturnedLate()) || Boolean.TRUE.equals(d.getLateAlerted());
        if (d.getOverLimitCount() >= 3 || d.getMaxSpeedKmh() >= limit + 30 || Boolean.TRUE.equals(d.getLeftArea())) return "RISKY";
        if (d.getOverLimitCount() > 0 || late) return "CHECK";
        return "CAREFUL";
    }

    /** "12.4 km in 26 min, top speed 84 km/h, over 80 km/h once, stayed within 5 km, back on time." */
    static String driveSummary(GuardedCar car, TestDrive d) {
        int limit = car.getSpeedLimitKmh() != null ? car.getSpeedLimitKmh() : 80;
        LocalDateTime end = d.getEndedAt() != null ? d.getEndedAt() : LocalDateTime.now();
        long minutes = Math.max(0, Duration.between(d.getStartedAt(), end).toMinutes());
        int over = d.getOverLimitCount();
        StringBuilder s = new StringBuilder();
        s.append(Math.round(d.getDistanceM() / 100.0) / 10.0).append(" km in ").append(minutes).append(" min, top speed ")
                .append(Math.round(d.getMaxSpeedKmh())).append(" km/h, ")
                .append(over == 0 ? "never over " + limit + " km/h" : "over " + limit + " km/h " + (over == 1 ? "once" : over + " times"))
                .append(", ").append(Boolean.TRUE.equals(d.getLeftArea()) ? "went beyond " : "stayed within ").append(km(d.getRadiusM()));
        if (d.getEndedAt() != null) s.append(Boolean.TRUE.equals(d.getReturnedLate()) ? ", back late." : ", back on time.");
        else s.append(".");
        return s.toString();
    }

    // ------------------------------------------------------------------ stolen

    @Transactional
    public Map<String, Object> reportStolen(User owner, Long carId, CarPointRequest where, String requestBase) {
        GuardedCar car = mine(owner, carId);
        if (car.stolen()) return view(car, requestBase);
        double[] p = null;
        if (where != null && where.getLatitude() != null && where.getLongitude() != null) {
            if (!validPoint(where.getLatitude(), where.getLongitude())) throw ApiException.badRequest("That location doesn't look right.");
            p = new double[]{where.getLatitude(), where.getLongitude()};
        }
        boolean tracked = car.getLastSeenAt() != null && car.getLastSeenAt().isAfter(now().minusDays(2));
        if (tracked) p = new double[]{car.getLastLatitude(), car.getLastLongitude()}; // the tracker knows better
        if (p == null && car.getHomeLatitude() != null) p = new double[]{car.getHomeLatitude(), car.getHomeLongitude()};
        if (p == null) throw ApiException.badRequest("Where was it last? Turn on location, or set where it's parked first.");

        String link = base(requestBase) + "/car.html?t=" + car.getTrackToken();
        StringBuilder desc = new StringBuilder("Stolen: ").append(car.describe()).append(". ")
                .append(tracked ? "Live position: " : "Details and sightings: ").append(link);
        if (owner.getPhone() != null) desc.append(" Owner: ").append(owner.getName()).append(", ").append(owner.getPhone()).append(".");
        if (where != null && where.getNote() != null && !where.getNote().isBlank()) desc.append(" ").append(where.getNote().trim());
        IncidentRequest r = new IncidentRequest();
        r.setType(IncidentType.VEHICLE_THEFT);
        r.setLatitude(p[0]);
        r.setLongitude(p[1]);
        r.setVehicleKind("CAR");
        r.setDescription(desc.length() <= 500 ? desc.toString() : desc.substring(0, 497) + "...");
        List<IncidentResponse> made = incidents.create(owner, r, "CARGUARD", null, false);

        LocalDateTime now = now();
        car.setTheftIncidentId(made.isEmpty() ? null : made.get(0).getId());
        car.setStatus("STOLEN");
        car.setStolenAt(now);
        car.setRecoveredAt(null);
        car.setLastBroadcastAt(now);
        car.setSilentAlerted(false);
        drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").ifPresent(d -> finish(d, now));
        cars.save(car);

        int nearby = broadcastStolen(car, p[0], p[1]);
        tellOwner(car, RiskLevel.HIGH, CAR_GUARD + ": " + car.getName() + " reported stolen. The police have its details"
                + (tracked ? " and live position" : "") + (nearby > 0 ? "; " + nearby + " Raksio users nearby are looking out" : "")
                + ". Don't go after it yourself.", p[0], p[1]);
        changed(car);
        Map<String, Object> v = view(car, requestBase);
        v.put("told", nearby);
        return v;
    }

    @Transactional
    public Map<String, Object> recovered(User owner, Long carId, String requestBase) {
        GuardedCar car = mine(owner, carId);
        if (!car.stolen()) return view(car, requestBase);
        LocalDateTime since = car.getStolenAt() != null ? car.getStolenAt() : now().minusDays(KEEP_DAYS);
        car.setStatus("NORMAL");
        car.setRecoveredAt(now());
        car.setLastBroadcastAt(null);
        cars.save(car);
        Long inc = car.getTheftIncidentId();
        if (inc != null) {
            incidents.tellAssignee(inc, CAR_FOUND + ": the owner has the " + car.describe() + " back. Thank you.");
            try {
                incidents.cancel(owner, inc);
            } catch (ApiException e) {
                log.info("Theft request {} was already closed", inc);
            }
        }
        // Thank (and reward) the people who spotted it.
        Set<Long> thanked = new HashSet<>();
        for (CarSighting s : sightings.findByCarIdAndAtAfterOrderByAtDesc(car.getId(), since)) {
            User u = s.getReporter();
            if (u == null || u.getId().equals(owner.getId()) || !thanked.add(u.getId())) continue;
            rewards.grant(u, SIGHTING_POINTS, "Helped find a stolen car", null);
            alerts.raiseAboutCar(u, RiskLevel.LOW, CAR_FOUND + ": the stolen " + car.describe()
                    + " you spotted is back with its owner. Thank you! +" + SIGHTING_POINTS + " points.", null, null, car.getId(), null);
        }
        changed(car);
        Map<String, Object> v = view(car, requestBase);
        v.put("thanked", thanked.size());
        return v;
    }

    /** Raksio users near a stolen car (seen in the last 15 min within 5 km), each at most every 30 minutes. */
    int broadcastStolen(GuardedCar car, double lat, double lon) {
        LocalDateTime now = now();
        int n = 0;
        for (Vehicle v : LiveRecipients.latestPerOwner(vehicles.findByStatus(VehicleStatus.ACTIVE),
                v -> !Boolean.TRUE.equals(v.getIsSimulated()) && v.seenWithin(now, LIVE_SECONDS))) {
            User u = v.getOwner();
            if (u.getId().equals(car.getOwner().getId()) || Boolean.FALSE.equals(u.getActive())) continue;
            double d = GeoUtil.distanceMeters(v.getCurrentLatitude(), v.getCurrentLongitude(), lat, lon);
            if (d > NEARBY_M) continue;
            String key = u.getId() + ":" + car.getId();
            if (!gapPassed(told.get(key), PER_PERSON_GAP, now)) continue;
            told.put(key, now);
            alerts.raiseAboutCar(u, RiskLevel.MEDIUM, STOLEN_CAR + ": " + car.describe() + ", last seen " + km(d)
                    + " from you. Don't approach it - if you see it, tap to tell the police.", lat, lon, car.getId(), car.getTheftIncidentId());
            n++;
        }
        return n;
    }

    /** "I saw it here": tells the owner and the police unit on the case. */
    @Transactional
    public Map<String, Object> sighting(User reporter, Long carId, CarPointRequest r) {
        GuardedCar car = cars.findById(carId).orElseThrow(() -> ApiException.notFound("Car not found"));
        if (!car.stolen()) throw ApiException.badRequest("This car isn't reported stolen any more - thank you for looking out.");
        if (r.getLatitude() == null || r.getLongitude() == null || !validPoint(r.getLatitude(), r.getLongitude())) {
            throw ApiException.badRequest("Turn on location so we can send where you saw it.");
        }
        LocalDateTime now = now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        if (sightings.existsByCarIdAndReporterIdAndAtAfter(car.getId(), reporter.getId(), now.minus(SIGHTING_GAP))) {
            m.put("already", true);
            m.put("message", "You've told us already - thank you. The police have it.");
            return m;
        }
        String note = r.getNote() != null && !r.getNote().isBlank() ? r.getNote().trim() : null;
        sightings.save(CarSighting.builder().carId(car.getId()).reporter(reporter).latitude(r.getLatitude())
                .longitude(r.getLongitude()).note(note).source("APP").at(now).build());
        spotted(car, r.getLatitude(), r.getLongitude(), "someone spotted your " + car.getName() + " just now"
                + (note != null ? " (\"" + note + "\")" : ""), "spotted by a Raksio user just now" + (note != null ? " (\"" + note + "\")" : ""));
        m.put("message", "Thank you - the owner and the police have been told. Don't approach the car or the people in it.");
        return m;
    }

    /** A sighting (app or plate report): the request moves there unless the tracker is live, the unit and the owner are told. */
    private void spotted(GuardedCar car, double lat, double lon, String forOwner, String forPolice) {
        boolean liveTracker = car.getLastSeenAt() != null && car.getLastSeenAt().isAfter(now().minusMinutes(10));
        Long inc = car.getTheftIncidentId();
        if (inc != null) {
            if (!liveTracker) incidents.moveTo(inc, lat, lon);
            incidents.tellAssignee(inc, STOLEN_SEEN + ": the " + car.describe() + " was " + forPolice
                    + (liveTracker ? "." : ". The request now shows that place."));
        }
        tellOwner(car, RiskLevel.HIGH, STOLEN_SEEN + ": " + forOwner + ". The police have been told - don't go there yourself.", lat, lon);
        changed(car);
    }

    /** What someone asked to look out for sees: the car, and roughly where it was last seen. */
    public Map<String, Object> lookout(User viewer, Long carId) {
        GuardedCar car = cars.findById(carId).orElseThrow(() -> ApiException.notFound("Car not found"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", car.getId());
        m.put("stolen", car.stolen());
        if (!car.stolen()) {
            m.put("message", car.getRecoveredAt() != null ? "This car has been found - thank you for looking out." : "This car isn't reported stolen.");
            return m;
        }
        m.put("describe", car.describe());
        m.put("plate", car.getPlate());
        m.put("model", car.getModel());
        m.put("colour", car.getColour());
        m.put("stolenAt", car.getStolenAt());
        double[] last = lastKnown(car);
        if (last != null) {
            // Rounded (about 100 m): enough to look out, not a live position for anyone with the app.
            m.put("lastLatitude", Math.round(last[0] * 1000) / 1000.0);
            m.put("lastLongitude", Math.round(last[1] * 1000) / 1000.0);
        }
        m.put("lastSeenAt", lastKnownAt(car));
        m.put("alreadySent", sightings.existsByCarIdAndReporterIdAndAtAfter(car.getId(), viewer.getId(), now().minus(SIGHTING_GAP)));
        m.put("mine", car.getOwner().getId().equals(viewer.getId()));
        return m;
    }

    private double[] lastKnown(GuardedCar car) {
        LocalDateTime trackerAt = car.getLastSeenAt();
        CarSighting s = latestSighting(car);
        if (s != null && (trackerAt == null || s.getAt().isAfter(trackerAt))) return new double[]{s.getLatitude(), s.getLongitude()};
        if (car.getLastLatitude() != null) return new double[]{car.getLastLatitude(), car.getLastLongitude()};
        if (car.getHomeLatitude() != null) return new double[]{car.getHomeLatitude(), car.getHomeLongitude()};
        return null;
    }

    private LocalDateTime lastKnownAt(GuardedCar car) {
        CarSighting s = latestSighting(car);
        LocalDateTime t = car.getLastSeenAt();
        if (s != null && (t == null || s.getAt().isAfter(t))) return s.getAt();
        return t != null ? t : car.getStolenAt();
    }

    private CarSighting latestSighting(GuardedCar car) {
        if (car.getStolenAt() == null) return null;
        List<CarSighting> list = sightings.findByCarIdAndAtAfterOrderByAtDesc(car.getId(), car.getStolenAt());
        return list.isEmpty() ? null : list.get(0);
    }

    /** /car.html?t=... - the police's page while it's stolen (nothing at all otherwise). */
    public Map<String, Object> publicView(String token) {
        GuardedCar car = token == null || token.isBlank() ? null : cars.findByTrackToken(token.trim()).orElse(null);
        if (car == null) throw ApiException.notFound("This link isn't valid.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("describe", car.describe());
        if (!car.stolen()) {
            if (car.getRecoveredAt() != null && car.getRecoveredAt().isAfter(now().minusDays(2))) {
                m.put("found", true);
                m.put("recoveredAt", car.getRecoveredAt());
                return m;
            }
            throw ApiException.notFound("This car isn't reported stolen.");
        }
        m.put("found", false);
        m.put("plate", car.getPlate());
        m.put("model", car.getModel());
        m.put("colour", car.getColour());
        m.put("stolenAt", car.getStolenAt());
        m.put("owner", car.getOwner().getName());
        m.put("ownerPhone", car.getOwner().getPhone());
        m.put("tracked", car.getLastSeenAt() != null);
        if (car.getLastSeenAt() != null) {
            m.put("last", lastMap(car));
            m.put("online", car.getLastSeenAt().isAfter(now().minus(ONLINE)));
        }
        LocalDateTime from = car.getStolenAt().minusHours(6);
        m.put("trail", points(positions.findByCarIdAndAtAfterOrderByAtAsc(car.getId(), from)));
        m.put("sightings", sightings.findByCarIdAndAtAfterOrderByAtDesc(car.getId(), car.getStolenAt()).stream().limit(50).map(s -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("latitude", s.getLatitude());
            x.put("longitude", s.getLongitude());
            x.put("at", s.getAt());
            x.put("source", s.getSource());
            x.put("note", s.getNote());
            return x;
        }).toList());
        if (car.getTheftIncidentId() != null) m.put("request", incidents.statusOf(car.getTheftIncidentId()));
        return m;
    }

    /** Admin > Emergencies: every car reported stolen right now. */
    public List<Map<String, Object>> stolenForAdmin(User admin, String requestBase) {
        AdminControlService.requireAdmin(admin);
        return cars.findByStatus("STOLEN").stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("describe", c.describe());
            m.put("plate", c.getPlate());
            m.put("owner", c.getOwner().getName());
            m.put("ownerPhone", c.getOwner().getPhone());
            m.put("stolenAt", c.getStolenAt());
            double[] p = lastKnown(c);
            if (p != null) {
                m.put("latitude", p[0]);
                m.put("longitude", p[1]);
            }
            m.put("lastSeenAt", lastKnownAt(c));
            m.put("online", c.getLastSeenAt() != null && c.getLastSeenAt().isAfter(now().minus(ONLINE)));
            m.put("speedKmh", c.getLastSpeedKmh() != null ? Math.round(c.getLastSpeedKmh()) : null);
            m.put("sightings", sightings.findByCarIdAndAtAfterOrderByAtDesc(c.getId(),
                    c.getStolenAt() != null ? c.getStolenAt() : now().minusDays(KEEP_DAYS)).size());
            m.put("request", c.getTheftIncidentId() != null ? incidents.statusOf(c.getTheftIncidentId()) : null);
            m.put("trackUrl", base(requestBase) + "/car.html?t=" + c.getTrackToken());
            return m;
        }).toList();
    }

    // ------------------------------------------------------------------ trail, demo, hand-over

    public Map<String, Object> trail(User owner, Long carId, int hours) {
        GuardedCar car = mine(owner, carId);
        int h = Math.max(1, Math.min(72, hours));
        List<CarPosition> pts = positions.findByCarIdAndAtAfterOrderByAtAsc(car.getId(), now().minusHours(h));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hours", h);
        m.put("points", points(pts));
        m.put("trips", tripsOf(pts));
        return m;
    }

    /** "Try it with this phone": the owner's phone sends a position as the car's tracker would. */
    @Transactional
    public Map<String, Object> phoneFix(User owner, Long carId, CarPointRequest r) {
        GuardedCar car = mine(owner, carId);
        if (r.getLatitude() == null || r.getLongitude() == null) throw ApiException.badRequest("Turn on location first.");
        ingest(new Fix(car.getTrackerId(), r.getLatitude(), r.getLongitude(), r.getSpeedKmh(), r.getHeading(), null, null, now(), null));
        return view(cars.findById(car.getId()).orElse(car), null);
    }

    /**
     * A showroom sells a car with its tracker: the car moves to the buyer's
     * Raksio account. The buyer gets no history (trail, test drives) and the
     * guard starts fresh.
     */
    @Transactional
    public Map<String, Object> transfer(User owner, Long carId, CarTransferRequest r) {
        GuardedCar car = mine(owner, carId);
        if (car.stolen()) throw ApiException.badRequest("It's reported stolen - mark it found first.");
        if (drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").isPresent()) throw ApiException.badRequest("End the test drive first.");
        String to = r.getTo().trim();
        User buyer = to.contains("@")
                ? users.findByEmail(to.toLowerCase(Locale.ROOT)).or(() -> users.findByEmail(to)).orElse(null)
                : users.findByPhone(ContactService.normalise(to)).stream().findFirst().orElse(null);
        if (buyer == null) throw ApiException.notFound("No Raksio account with that email or number. Ask the buyer to sign up first.");
        if (buyer.getId().equals(owner.getId())) throw ApiException.badRequest("That's your own account.");
        if (cars.countByOwnerId(buyer.getId()) >= MAX_CARS) throw ApiException.badRequest("The buyer already has " + MAX_CARS + " cars.");
        if (car.getPlateKey() != null && cars.findByOwnerIdOrderByCreatedAtAsc(buyer.getId()).stream()
                .anyMatch(o -> car.getPlateKey().equals(o.getPlateKey()))) {
            throw ApiException.badRequest("The buyer has already added " + car.getPlate() + ".");
        }
        positions.deleteByCarId(car.getId());
        car.setOwner(buyer);
        car.setName(car.getModel() != null ? car.getModel() : "My car");
        car.setGuardOn(false);
        car.setNightGuard(true);
        car.setSpeedAlerts(false);
        car.setHomeLatitude(null);
        car.setHomeLongitude(null);
        car.setHomeRadiusM(null);
        car.setParkedLatitude(null);
        car.setParkedLongitude(null);
        car.setTrackToken(uniqueToken());
        car.setLastMoveAlertAt(null);
        car.setLastSpeedAlertAt(null);
        cars.save(car);
        alerts.raiseAboutCar(buyer, RiskLevel.LOW, CAR_GUARD + ": " + owner.getName() + " has handed your " + car.describe()
                + " over to you, with its tracker. Open Car guard to set where you park it.", null, null, car.getId(), null);
        changed(car);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("to", buyer.getName());
        return m;
    }

    // ------------------------------------------------------------------ every minute

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void tick() {
        try {
            checkSilence();
        } catch (RuntimeException e) {
            log.warn("Car guard silence check failed: {}", e.toString());
        }
        try {
            checkTestDrives();
        } catch (RuntimeException e) {
            log.warn("Car guard test-drive check failed: {}", e.toString());
        }
        try {
            plateWatch();
        } catch (RuntimeException e) {
            log.warn("Car guard plate watch failed: {}", e.toString());
        }
        LocalDateTime cut = now().minusHours(2);
        told.values().removeIf(t -> t.isBefore(cut));
        alarmed.values().removeIf(t -> t.isBefore(cut));
    }

    /** Trackers that went quiet while it mattered. Returns how many owners were told. */
    @Transactional
    public int checkSilence() {
        LocalDateTime now = now();
        int n = 0;
        for (GuardedCar car : cars.findAll()) {
            if (car.getLastSeenAt() == null || Boolean.TRUE.equals(car.getSilentAlerted())) continue;
            TestDrive drive = drives.findFirstByCarIdAndStatus(car.getId(), "ACTIVE").orElse(null);
            boolean wasMoving = (car.getLastSpeedKmh() != null && car.getLastSpeedKmh() >= MOVING_KMH)
                    || Boolean.TRUE.equals(car.getLastIgnition());
            Duration limit = car.stolen() || drive != null || wasMoving ? SILENT_MOVING
                    : Boolean.TRUE.equals(car.getGuardOn()) ? SILENT_PARKED : null;
            if (limit == null) continue;
            Duration quiet = Duration.between(car.getLastSeenAt(), now);
            if (quiet.compareTo(limit) < 0) continue;
            car.setSilentAlerted(true);
            cars.save(car);
            if (quiet.toHours() >= 24) continue; // long gone quiet - nothing new to say
            long mins = quiet.toMinutes();
            String why = car.stolen() ? "It may have been switched off - the police still have its last position."
                    : "It may have been switched off or lost signal.";
            String text = CAR_GUARD + ": " + car.getName() + "'s tracker stopped sending " + mins + " min ago"
                    + where(car, car.getLastLatitude(), car.getLastLongitude()) + ". " + why;
            if (drive != null) tell(drive.getStartedBy(), car, RiskLevel.HIGH, text, car.getLastLatitude(), car.getLastLongitude());
            else tellOwner(car, RiskLevel.HIGH, text, car.getLastLatitude(), car.getLastLongitude());
            if (car.stolen() && car.getTheftIncidentId() != null) {
                incidents.tellAssignee(car.getTheftIncidentId(), STOLEN_CAR + ": the " + car.describe() + "'s tracker went quiet "
                        + mins + " min ago. Its last position is on the request.");
            }
            n++;
        }
        return n;
    }

    /** Customers late back; drives left running for hours are closed. */
    @Transactional
    public int checkTestDrives() {
        LocalDateTime now = now();
        int n = 0;
        for (TestDrive d : drives.findByStatus("ACTIVE")) {
            GuardedCar car = cars.findById(d.getCarId()).orElse(null);
            if (car == null || now.isAfter(d.getDueBackAt().plusHours(6))) {
                finish(d, now);
                continue;
            }
            if (now.isAfter(d.getDueBackAt()) && !Boolean.TRUE.equals(d.getLateAlerted())) {
                d.setLateAlerted(true);
                drives.save(d);
                String where = "";
                if (car.getLastLatitude() != null) {
                    double km = GeoUtil.distanceMeters(d.getCenterLatitude(), d.getCenterLongitude(), car.getLastLatitude(), car.getLastLongitude());
                    where = " It's " + km(km) + " from the showroom"
                            + (car.getLastSpeedKmh() != null && car.getLastSpeedKmh() >= MOVING_KMH ? " and moving." : ".");
                }
                tell(d.getStartedBy(), car, RiskLevel.MEDIUM, TEST_DRIVE + ": " + d.getCustomerName() + " should have been back with "
                        + car.getName() + " at " + clock(d.getDueBackAt()) + "." + where
                        + (d.getCustomerPhone() != null ? " Call them: " + d.getCustomerPhone() : ""),
                        car.getLastLatitude(), car.getLastLongitude());
                n++;
            }
        }
        return n;
    }

    /** New rule-breaker reports naming a stolen car's number plate count as sightings. */
    @Transactional
    public int plateWatch() {
        LocalDateTime from = plateCheckedUpTo;
        List<ViolationComplaint> fresh = complaints.findByCreatedAtAfterOrderByCreatedAtAsc(from);
        if (fresh.isEmpty()) return 0;
        plateCheckedUpTo = fresh.get(fresh.size() - 1).getCreatedAt();
        Map<String, GuardedCar> byPlate = new HashMap<>();
        for (GuardedCar c : cars.findByStatus("STOLEN")) if (c.getPlateKey() != null) byPlate.put(c.getPlateKey(), c);
        if (byPlate.isEmpty()) return 0;
        int n = 0;
        for (ViolationComplaint c : fresh) {
            GuardedCar car = byPlate.get(plateKey(c.getPlateNumber()));
            if (car == null || c.getLatitude() == null || c.getLongitude() == null) continue;
            String what = c.getType() != null ? c.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') : "a traffic rule";
            sightings.save(CarSighting.builder().carId(car.getId()).reporter(c.getReporter()).latitude(c.getLatitude())
                    .longitude(c.getLongitude()).note("Reported for " + what).source("REPORT").at(c.getCreatedAt()).build());
            spotted(car, c.getLatitude(), c.getLongitude(),
                    "a Raksio user reported a car with your number " + car.getPlate() + " (" + what + ") just now",
                    "reported by a Raksio user for " + what + " just now");
            n++;
        }
        return n;
    }

    /** Trail older than 30 days goes (every night). */
    @Scheduled(cron = "0 35 3 * * *")
    public void cleanUp() {
        try {
            long n = positions.deleteByAtBefore(now().minusDays(KEEP_DAYS));
            if (n > 0) log.info("Car guard: removed {} old positions", n);
        } catch (RuntimeException e) {
            log.warn("Car guard clean-up failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ views

    Map<String, Object> view(GuardedCar c, String requestBase) {
        LocalDateTime now = now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("plate", c.getPlate());
        m.put("model", c.getModel());
        m.put("colour", c.getColour());
        m.put("describe", c.describe());
        m.put("status", c.getStatus());
        m.put("guardOn", c.getGuardOn());
        m.put("nightGuard", c.getNightGuard());
        m.put("nightFrom", c.getNightFrom());
        m.put("nightTo", c.getNightTo());
        m.put("speedAlerts", c.getSpeedAlerts());
        m.put("speedLimitKmh", limitOf(c));
        boolean nightNow = Boolean.TRUE.equals(c.getNightGuard()) && isNight(c, now);
        m.put("armed", !c.stolen() && (Boolean.TRUE.equals(c.getGuardOn()) || nightNow));
        m.put("nightNow", nightNow);
        if (c.getHomeLatitude() != null) {
            m.put("home", Map.of("latitude", c.getHomeLatitude(), "longitude", c.getHomeLongitude(),
                    "radiusM", c.getHomeRadiusM() != null ? c.getHomeRadiusM() : 150));
        } else {
            m.put("home", null);
        }
        m.put("trackerId", c.getTrackerId());
        boolean connected = c.getLastSeenAt() != null;
        boolean online = connected && c.getLastSeenAt().isAfter(now.minus(ONLINE));
        m.put("connected", connected);
        m.put("online", online);
        m.put("lastSeenAt", c.getLastSeenAt());
        m.put("minutesAgo", connected ? Duration.between(c.getLastSeenAt(), now).toMinutes() : null);
        m.put("battery", c.getLastBattery());
        m.put("ignition", c.getLastIgnition());
        m.put("last", connected ? lastMap(c) : null);
        if (c.getHomeLatitude() != null && connected) {
            double d = GeoUtil.distanceMeters(c.getHomeLatitude(), c.getHomeLongitude(), c.getLastLatitude(), c.getLastLongitude());
            m.put("fromHomeM", Math.round(d));
            m.put("atHome", d <= (c.getHomeRadiusM() != null ? c.getHomeRadiusM() : 150));
        }
        TestDrive drive = drives.findFirstByCarIdAndStatus(c.getId(), "ACTIVE").orElse(null);
        m.put("testDrive", drive != null ? driveView(c, drive) : null);
        String state;
        if (c.stolen()) state = "STOLEN";
        else if (drive != null) state = "TEST_DRIVE";
        else if (!connected) state = "NO_TRACKER";
        else if (c.getLastSeenAt().isBefore(now.minusMinutes(30))) state = "OFFLINE";
        else if (online && c.getLastSpeedKmh() != null && c.getLastSpeedKmh() >= 5) state = "MOVING";
        else state = "PARKED";
        m.put("state", state);
        m.put("stolenAt", c.getStolenAt());
        m.put("recoveredAt", c.getRecoveredAt());
        if (c.stolen()) {
            m.put("request", c.getTheftIncidentId() != null ? incidents.statusOf(c.getTheftIncidentId()) : null);
            m.put("trackUrl", base(requestBase) + "/car.html?t=" + c.getTrackToken());
            List<CarSighting> seen = c.getStolenAt() != null
                    ? sightings.findByCarIdAndAtAfterOrderByAtDesc(c.getId(), c.getStolenAt()) : List.of();
            m.put("sightings", seen.stream().limit(20).map(s -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("latitude", s.getLatitude());
                x.put("longitude", s.getLongitude());
                x.put("at", s.getAt());
                x.put("source", s.getSource());
                x.put("note", s.getNote());
                return x;
            }).toList());
        }
        return m;
    }

    private static Map<String, Object> lastMap(GuardedCar c) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("latitude", c.getLastLatitude());
        l.put("longitude", c.getLastLongitude());
        l.put("speedKmh", c.getLastSpeedKmh() != null ? Math.round(c.getLastSpeedKmh()) : null);
        l.put("heading", c.getLastHeading());
        l.put("at", c.getLastSeenAt());
        return l;
    }

    private static List<Map<String, Object>> points(List<CarPosition> pts) {
        int step = Math.max(1, (int) Math.ceil(pts.size() / (double) MAX_TRAIL_POINTS));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (i % step != 0 && i != pts.size() - 1) continue;
            CarPosition p = pts.get(i);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("latitude", p.getLatitude());
            x.put("longitude", p.getLongitude());
            x.put("speedKmh", p.getSpeedKmh() != null ? Math.round(p.getSpeedKmh()) : null);
            x.put("at", p.getAt());
            out.add(x);
        }
        return out;
    }

    /**
     * Trips in a trail: moving stretches, ended by standing still 3 minutes or
     * the tracker going quiet 15 minutes. Shorter than 300 m doesn't count.
     */
    static List<Map<String, Object>> tripsOf(List<CarPosition> pts) {
        List<Map<String, Object>> trips = new ArrayList<>();
        CarPosition prev = null;
        CarPosition start = null;
        CarPosition end = null;
        double dist = 0;
        double max = 0;
        for (CarPosition p : pts) {
            if (prev != null) {
                long gap = Math.max(1, Duration.between(prev.getAt(), p.getAt()).getSeconds());
                double d = GeoUtil.distanceMeters(prev.getLatitude(), prev.getLongitude(), p.getLatitude(), p.getLongitude());
                if (gap > 15 * 60) {
                    // The tracker was off: whatever came before is a separate trip, and nothing is drawn across the gap.
                    if (start != null) addTrip(trips, start, end, dist, max);
                    start = null;
                    prev = p;
                    continue;
                }
                double v = p.getSpeedKmh() != null ? p.getSpeedKmh() : d / gap * 3.6;
                boolean moving = v >= 5 && d > 15;
                if (moving) {
                    if (start == null) {
                        start = prev;
                        dist = 0;
                        max = 0;
                    }
                    dist += d;
                    max = Math.max(max, v);
                    end = p;
                } else if (start != null && Duration.between(end.getAt(), p.getAt()).toMinutes() >= 3) {
                    addTrip(trips, start, end, dist, max);
                    start = null;
                }
            }
            prev = p;
        }
        if (start != null) addTrip(trips, start, end, dist, max);
        Collections.reverse(trips); // newest first
        return trips;
    }

    private static void addTrip(List<Map<String, Object>> trips, CarPosition start, CarPosition end, double dist, double max) {
        if (dist < 300) return;
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("startAt", start.getAt());
        t.put("endAt", end.getAt());
        t.put("minutes", Duration.between(start.getAt(), end.getAt()).toMinutes());
        t.put("distanceKm", Math.round(dist / 100.0) / 10.0);
        t.put("maxSpeedKmh", Math.round(max));
        t.put("from", Map.of("latitude", start.getLatitude(), "longitude", start.getLongitude()));
        t.put("to", Map.of("latitude", end.getLatitude(), "longitude", end.getLongitude()));
        trips.add(t);
    }

    // ------------------------------------------------------------------ helpers

    private GuardedCar mine(User owner, Long carId) {
        GuardedCar car = cars.findById(carId).orElseThrow(() -> ApiException.notFound("Car not found"));
        if (!car.getOwner().getId().equals(owner.getId())) throw ApiException.notFound("Car not found");
        return car;
    }

    private void tellOwner(GuardedCar car, RiskLevel level, String text, Double lat, Double lon) {
        tell(car.getOwner(), car, level, text, lat, lon);
    }

    private void tell(User to, GuardedCar car, RiskLevel level, String text, Double lat, Double lon) {
        if (to == null) return;
        alerts.raiseAboutCar(to, level, text, lat, lon, car.getId(), car.stolen() ? car.getTheftIncidentId() : null);
    }

    /** Tells the owner's app to refresh this car (no details - it fetches what it may see). */
    private void changed(GuardedCar car) {
        webSocket.broadcast("CAR", Map.of("id", car.getId(), "ownerId", car.getOwner().getId()));
    }

    /** ", 2.4 km from home" / ", at home" - or nothing when it has no home. */
    private static String where(GuardedCar car, Double lat, Double lon) {
        if (car.getHomeLatitude() == null || lat == null || lon == null) return "";
        double d = GeoUtil.distanceMeters(car.getHomeLatitude(), car.getHomeLongitude(), lat, lon);
        if (d <= (car.getHomeRadiusM() != null ? car.getHomeRadiusM() : 150)) return ", at its parking place";
        return ", " + km(d) + " from its parking place";
    }

    static boolean isNight(GuardedCar car, LocalDateTime at) {
        int from = car.getNightFrom() != null ? car.getNightFrom() : 22;
        int to = car.getNightTo() != null ? car.getNightTo() : 6;
        int h = at.atZone(ZoneId.systemDefault()).withZoneSameInstant(IST).getHour();
        if (from == to) return false;
        return from > to ? h >= from || h < to : h >= from && h < to;
    }

    private static String clock(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).withZoneSameInstant(IST).format(CLOCK).toLowerCase(Locale.ROOT);
    }

    /** "north-east " (with the space) or "" when the tracker doesn't say. */
    private static String compass(Double heading) {
        if (heading == null || heading.isNaN()) return "";
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(((heading % 360) + 360) % 360 / 45.0) % 8] + " ";
    }

    static String km(double meters) {
        long tens = Math.round(meters / 10.0) * 10;
        if (tens < 1000) return tens + " m";
        String km = meters < 10_000 ? String.format(Locale.ROOT, "%.1f", meters / 1000) : String.valueOf(Math.round(meters / 1000));
        return (km.endsWith(".0") ? km.substring(0, km.length() - 2) : km) + " km";
    }

    private static int limitOf(GuardedCar car) {
        return car.getSpeedLimitKmh() != null ? car.getSpeedLimitKmh() : 80;
    }

    private static boolean gapPassed(LocalDateTime last, Duration gap, LocalDateTime now) {
        return last == null || !last.isAfter(now.minus(gap));
    }

    private static boolean validPoint(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    /** "AP 16 BX 2231", "ap16-bx-2231" -> "AP16BX2231" (null if nothing's left). */
    public static String plateKey(String plate) {
        if (plate == null) return null;
        String k = plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (k.isEmpty()) return null;
        return k.length() > 20 ? k.substring(0, 20) : k;
    }

    private String freeTrackerId(String raw, Long exceptCarId) {
        String id = raw.trim();
        if (!id.matches("[A-Za-z0-9_.:-]{6,40}")) {
            throw ApiException.badRequest("A tracker id is 6-40 letters, digits, dots, dashes or underscores.");
        }
        cars.findByTrackerId(id).filter(o -> !o.getId().equals(exceptCarId)).ifPresent(o -> {
            throw ApiException.badRequest("Another car already uses that tracker id.");
        });
        return id;
    }

    private String uniqueTrackerId() {
        for (int i = 0; i < 10; i++) {
            String id = random(20);
            if (cars.findByTrackerId(id).isEmpty()) return id;
        }
        throw new IllegalStateException("Couldn't make a tracker id");
    }

    private String uniqueToken() {
        for (int i = 0; i < 10; i++) {
            String t = random(24);
            if (cars.findByTrackToken(t).isEmpty()) return t;
        }
        throw new IllegalStateException("Couldn't make a link");
    }

    private static String random(int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return s.toString();
    }

    private static void range(Integer v, int min, int max, String message) {
        if (v != null && (v < min || v > max)) throw ApiException.badRequest(message);
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstText(String... options) {
        for (String o : options) if (text(o) != null) return text(o);
        return null;
    }

    String base(String requestBase) {
        String b = publicUrl != null && !publicUrl.isBlank() ? publicUrl.trim() : (requestBase != null ? requestBase : "");
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
