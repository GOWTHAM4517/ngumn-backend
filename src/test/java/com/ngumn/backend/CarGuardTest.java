package com.ngumn.backend;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.notify.AuthorityForwarder;
import com.ngumn.backend.notify.NotifyGateway;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.service.*;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Car guard: trackers, the guard, speed and alarms, test drives, stolen cars
 * (police, people nearby, sightings, plate watch, found), silence, handing a
 * car to its buyer - the real services against in-memory repositories:
 *
 *   ./mvnw -Dtest='CarGuardTest' test
 */
class CarGuardTest {

    static final double SHOWROOM_LAT = 16.5062, SHOWROOM_LON = 80.6480; // Benz Circle, Vijayawada

    InMemoryRepositories db;
    UserRepository users;
    VehicleRepository vehicles;
    IncidentService incidents;
    CarGuardService guard;
    User showroom, ravi, farAway, police, admin, buyer;

    @BeforeEach
    void setUp() {
        db = new InMemoryRepositories();
        users = db.of(UserRepository.class);
        vehicles = db.of(VehicleRepository.class);
        NgumnWebSocketHandler ws = new NgumnWebSocketHandler();
        AlertService alerts = new AlertService(db.of(AlertRepository.class), ws, e -> { });
        EmergencyService corridor = new EmergencyService(db.of(EmergencyEventRepository.class), vehicles, alerts, null, ws);
        AuthorityForwarder forwarder = new AuthorityForwarder(new StandardEnvironment(), new NotifyGateway());
        incidents = new IncidentService(db.of(IncidentRepository.class), users, alerts, ws, vehicles, corridor, forwarder, e -> { });
        RewardService rewards = new RewardService(db.of(RewardRepository.class), users);
        guard = new CarGuardService(db.of(GuardedCarRepository.class), db.of(CarPositionRepository.class),
                db.of(TestDriveRepository.class), db.of(CarSightingRepository.class), db.of(ViolationComplaintRepository.class),
                vehicles, users, alerts, incidents, rewards, ws);

        showroom = person("Vijay Kumar (U-Trust)", "+919848011111", null, null);
        ravi = person("Ravi", "+919848022222", null, null);
        farAway = person("Far Away", null, null, null);
        buyer = person("Buyer", "+919848033333", null, null);
        police = person("Ramesh", null, ResponderTeam.POLICE, "Traffic Police - Benz Circle");
        admin = users.save(User.builder().name("Admin").email("admin@x").role(Role.ADMIN).build());
        live(ravi, north(SHOWROOM_LAT, 1000), SHOWROOM_LON);      // 1 km away, app open
        live(farAway, north(SHOWROOM_LAT, 20_000), SHOWROOM_LON); // 20 km away
    }

    User person(String name, String phone, ResponderTeam team, String unit) {
        return users.save(User.builder().name(name).email(name.toLowerCase().replaceAll("[^a-z]", "") + "@x").role(Role.DRIVER)
                .phone(phone).responderTeam(team).unitName(unit).rewardPoints(0).build());
    }

    void live(User u, double lat, double lon) {
        vehicles.save(Vehicle.builder().vehicleCode("U" + u.getId()).owner(u).vehicleType(VehicleType.CAR)
                .currentLatitude(lat).currentLongitude(lon).lastLocationUpdate(LocalDateTime.now().minusSeconds(30)).build());
    }

    static double north(double lat, double meters) {
        return lat + meters / 111_320.0;
    }

    Map<String, Object> addCar(User owner, String plate, boolean home) {
        CarRequest r = new CarRequest();
        r.setName("Stock " + plate);
        r.setPlate(plate);
        r.setModel("Toyota Innova");
        r.setColour("White");
        if (home) {
            r.setHomeLatitude(SHOWROOM_LAT);
            r.setHomeLongitude(SHOWROOM_LON);
            r.setHomeRadiusM(150);
        }
        return guard.add(owner, r, "https://raksio.test");
    }

    GuardedCar car(Map<String, Object> view) {
        return db.rows(GuardedCar.class).stream().filter(c -> c.getId().equals(view.get("id"))).findFirst().orElseThrow();
    }

    Map<String, Object> fix(GuardedCar car, double lat, double lon, Double kmh, LocalDateTime at) {
        return guard.ingest(new CarGuardService.Fix(car.getTrackerId(), lat, lon, kmh, 0.0, null, null, at, null));
    }

    List<Alert> alertsFor(User u) {
        return db.rows(Alert.class).stream().filter(a -> a.getUser() != null && a.getUser().getId().equals(u.getId())).toList();
    }

    List<Alert> alertsFor(User u, String headline) {
        return alertsFor(u).stream().filter(a -> a.getMessage().startsWith(headline + ":")).toList();
    }

    // ------------------------------------------------------------------ cars and trackers

    @Test
    void addingCarsMakesSecretIdsAndRefusesTheSamePlateTwice() {
        Map<String, Object> v = addCar(showroom, "AP 16 BX 2231", true);
        GuardedCar c = car(v);
        assertEquals(20, c.getTrackerId().length());
        assertEquals(24, c.getTrackToken().length());
        assertEquals("AP16BX2231", c.getPlateKey());
        assertEquals("White Toyota Innova, AP 16 BX 2231", c.describe());
        assertEquals("NO_TRACKER", v.get("state"));
        assertThrows(ApiException.class, () -> addCar(showroom, "ap16-bx-2231", false));
        // Another owner may (a car sold on) - and a fixed tracker id (a GT06's IMEI) is accepted once.
        assertDoesNotThrow(() -> addCar(buyer, "AP16BX2231", false));
        CarRequest imei = new CarRequest();
        imei.setName("Yard car");
        imei.setTrackerId("868120301234567");
        assertEquals("868120301234567", guard.add(showroom, imei, null).get("trackerId"));
        assertThrows(ApiException.class, () -> guard.add(buyer, imei, null));
        // Someone else's car is "not found".
        assertThrows(ApiException.class, () -> guard.get(ravi, c.getId(), null));
    }

    @Test
    void trackerFormatsAllBecomeTheSameFix() {
        CarGuardService.Fix osmand = TrackerFormats.parse(Map.of("id", "abc123", "lat", "16.5", "lon", "80.6",
                "speed", "10", "bearing", "90", "timestamp", "1793000000", "ignition", "true", "batt", "77"), null);
        assertEquals("abc123", osmand.trackerId());
        assertEquals(18.52, osmand.speedKmh(), 0.01); // knots
        assertEquals(true, osmand.ignition());
        assertEquals(77, osmand.battery());
        assertNotNull(osmand.at());

        CarGuardService.Fix client = TrackerFormats.parse(Map.of(), "{\"device_id\":\"abc123\",\"location\":{\"timestamp\":\"2026-11-04T10:00:00.000Z\","
                + "\"coords\":{\"latitude\":16.5,\"longitude\":80.6,\"speed\":10,\"heading\":-1},\"battery\":{\"level\":0.5}}}");
        assertEquals(36.0, client.speedKmh(), 0.01); // m/s
        assertNull(client.heading());
        assertEquals(50, client.battery());

        CarGuardService.Fix server = TrackerFormats.parse(Map.of(), "{\"device\":{\"uniqueId\":\"868120301234567\"},\"position\":{\"latitude\":16.5,"
                + "\"longitude\":80.6,\"speed\":20,\"course\":180,\"fixTime\":\"2026-11-04T10:00:00.000+00:00\","
                + "\"attributes\":{\"ignition\":false,\"batteryLevel\":64,\"alarm\":\"powerCut\"}}}");
        assertEquals("868120301234567", server.trackerId());
        assertEquals(37.04, server.speedKmh(), 0.01);
        assertEquals(false, server.ignition());
        assertEquals("powerCut", server.alarm());

        assertThrows(ApiException.class, () -> guard.ingest(osmand)); // no car uses that id
        assertThrows(ApiException.class, () -> TrackerFormats.parse(Map.of("id", "x"), null)); // no position
    }

    // ------------------------------------------------------------------ guard, speed, alarms

    @Test
    void guardTellsTheOwnerWhenTheCarMovesButNotWhenTheOwnerIsInIt() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        CarRequest on = new CarRequest();
        on.setGuardOn(true);
        guard.update(showroom, c.getId(), on, null);
        LocalDateTime t = LocalDateTime.now().minusMinutes(4);
        fix(c, SHOWROOM_LAT, SHOWROOM_LON, 0.0, t);
        fix(c, SHOWROOM_LAT + 0.00005, SHOWROOM_LON, 2.0, t.plusMinutes(1)); // GPS drift while parked
        assertTrue(alertsFor(showroom, CarGuardService.CAR_GUARD).isEmpty());

        fix(c, north(SHOWROOM_LAT, 400), SHOWROOM_LON, 40.0, t.plusMinutes(3));
        List<Alert> a = alertsFor(showroom, CarGuardService.CAR_GUARD);
        assertEquals(1, a.size());
        assertTrue(a.get(0).getMessage().contains("is moving (40 km/h)"), a.get(0).getMessage());
        assertTrue(a.get(0).getMessage().contains("400 m from its parking place"), a.get(0).getMessage());
        assertEquals(c.getId(), a.get(0).getCarId());
        fix(c, north(SHOWROOM_LAT, 700), SHOWROOM_LON, 45.0, t.plusMinutes(3).plusSeconds(30));
        assertEquals(1, alertsFor(showroom, CarGuardService.CAR_GUARD).size(), "once per 10 minutes");

        // A second car, with the owner's own phone travelling in it: no alarm.
        GuardedCar c2 = car(addCar(showroom, "AP 16 CC 1111", true));
        guard.update(showroom, c2.getId(), on, null);
        live(showroom, north(SHOWROOM_LAT, 900), SHOWROOM_LON);
        fix(c2, north(SHOWROOM_LAT, 950), SHOWROOM_LON, 35.0, LocalDateTime.now());
        assertEquals(1, alertsFor(showroom, CarGuardService.CAR_GUARD).size());
    }

    @Test
    void nightIsTheOwnersHours() {
        GuardedCar c = GuardedCar.builder().nightFrom(22).nightTo(6).build();
        LocalDateTime day = LocalDateTime.of(2026, 11, 4, 12, 0);
        java.time.ZoneId here = java.time.ZoneId.systemDefault();
        java.util.function.Function<Integer, LocalDateTime> ist = h -> day.withHour(h)
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).withZoneSameInstant(here).toLocalDateTime();
        assertTrue(CarGuardService_isNight(c, ist.apply(23)));
        assertTrue(CarGuardService_isNight(c, ist.apply(2)));
        assertFalse(CarGuardService_isNight(c, ist.apply(6)));
        assertFalse(CarGuardService_isNight(c, ist.apply(14)));
        GuardedCar yard = GuardedCar.builder().nightFrom(20).nightTo(23).build();
        assertTrue(CarGuardService_isNight(yard, ist.apply(21)));
        assertFalse(CarGuardService_isNight(yard, ist.apply(23)));
    }

    static boolean CarGuardService_isNight(GuardedCar c, LocalDateTime at) {
        try {
            var m = CarGuardService.class.getDeclaredMethod("isNight", GuardedCar.class, LocalDateTime.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, c, at);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void speedAlertsAndTrackerAlarms() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        CarRequest r = new CarRequest();
        r.setSpeedAlerts(true);
        r.setSpeedLimitKmh(60);
        r.setNightGuard(false);
        guard.update(showroom, c.getId(), r, null);
        LocalDateTime t = LocalDateTime.now().minusMinutes(2);
        fix(c, SHOWROOM_LAT, SHOWROOM_LON, 50.0, t);
        assertTrue(alertsFor(showroom).isEmpty());
        fix(c, north(SHOWROOM_LAT, 500), SHOWROOM_LON, 75.0, t.plusSeconds(30));
        List<Alert> a = alertsFor(showroom, CarGuardService.CAR_GUARD);
        assertEquals(1, a.size());
        assertTrue(a.get(0).getMessage().contains("75 km/h (your limit is 60)"), a.get(0).getMessage());

        guard.ingest(new CarGuardService.Fix(c.getTrackerId(), north(SHOWROOM_LAT, 600), SHOWROOM_LON, 20.0, null, null, 40,
                t.plusSeconds(60), "powerCut"));
        assertTrue(alertsFor(showroom, CarGuardService.CAR_GUARD).stream().anyMatch(x -> x.getMessage().contains("lost power")));
        Map<String, Object> v = guard.get(showroom, c.getId(), null);
        assertEquals(40, v.get("battery"));
        assertEquals(true, v.get("online"));
    }

    // ------------------------------------------------------------------ test drives

    @Test
    void testDriveIsWatchedAndEndsByItselfBackAtTheShowroom() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        TestDriveRequest r = new TestDriveRequest();
        r.setCustomerName("Suresh Babu");
        r.setCustomerPhone("98480 44444");
        r.setMinutes(30);
        r.setRadiusM(1000);
        Map<String, Object> d = guard.startTestDrive(showroom, c.getId(), r);
        assertEquals("ACTIVE", d.get("status"));
        assertEquals("+919848044444", d.get("customerPhone"));
        assertThrows(ApiException.class, () -> guard.startTestDrive(showroom, c.getId(), r), "one at a time");
        assertEquals("TEST_DRIVE", guard.get(showroom, c.getId(), null).get("state"));

        LocalDateTime t = LocalDateTime.now().plusSeconds(5);
        fix(c, SHOWROOM_LAT, SHOWROOM_LON, 5.0, t);
        fix(c, north(SHOWROOM_LAT, 600), SHOWROOM_LON, 70.0, t.plusSeconds(30));
        fix(c, north(SHOWROOM_LAT, 1400), SHOWROOM_LON, 95.0, t.plusSeconds(60));  // over 80, beyond 1 km
        fix(c, north(SHOWROOM_LAT, 2000), SHOWROOM_LON, 97.0, t.plusSeconds(80));  // still over: counted once
        List<Alert> a = alertsFor(showroom, CarGuardService.TEST_DRIVE);
        assertEquals(2, a.size(), a.toString());
        assertTrue(a.stream().anyMatch(x -> x.getMessage().contains("Suresh Babu is doing 95 km/h")));
        assertTrue(a.stream().anyMatch(x -> x.getMessage().contains("from the showroom (allowed 1 km)")));
        // The guard stays quiet during a test drive.
        assertTrue(alertsFor(showroom, CarGuardService.CAR_GUARD).isEmpty());

        fix(c, north(SHOWROOM_LAT, 900), SHOWROOM_LON, 40.0, t.plusSeconds(110));
        fix(c, north(SHOWROOM_LAT, 20), SHOWROOM_LON, 0.0, t.plusSeconds(140));
        fix(c, north(SHOWROOM_LAT, 25), SHOWROOM_LON, 0.0, t.plusSeconds(200));
        fix(c, north(SHOWROOM_LAT, 22), SHOWROOM_LON, 0.0, t.plusSeconds(270)); // parked 2+ min: back
        List<Map<String, Object>> history = guard.testDrives(showroom, c.getId());
        assertEquals(1, history.size());
        Map<String, Object> done = history.get(0);
        assertEquals("ENDED", done.get("status"));
        assertEquals(1, done.get("overLimitCount"));
        assertEquals(97, done.get("maxSpeedKmh"));
        assertEquals("RISKY", done.get("verdict")); // left the area
        assertTrue((double) done.get("distanceKm") > 3.5, done.toString());
        assertTrue(alertsFor(showroom, CarGuardService.TEST_DRIVE).stream().anyMatch(x -> x.getMessage().contains("is back with")));
        assertNull(guard.get(showroom, c.getId(), null).get("testDrive"));
    }

    @Test
    void lateTestDriveTellsTheStaffOnce() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        TestDriveRequest r = new TestDriveRequest();
        r.setCustomerName("Late Larry");
        r.setCustomerPhone("9848055555");
        r.setMinutes(10);
        guard.startTestDrive(showroom, c.getId(), r);
        TestDrive d = db.rows(TestDrive.class).get(0);
        d.setDueBackAt(LocalDateTime.now().minusMinutes(1));
        assertEquals(1, guard.checkTestDrives());
        assertEquals(0, guard.checkTestDrives());
        Alert a = alertsFor(showroom, CarGuardService.TEST_DRIVE).get(0);
        assertTrue(a.getMessage().contains("Late Larry should have been back"), a.getMessage());
        assertTrue(a.getMessage().contains("+919848055555"), a.getMessage());
        assertEquals("CHECK", guard.endTestDrive(showroom, c.getId()).get("verdict"));
    }

    // ------------------------------------------------------------------ stolen

    @Test
    void stolenCarGoesToThePoliceAndPeopleNearbyThenFound() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        CarPointRequest where = new CarPointRequest();
        where.setLatitude(SHOWROOM_LAT);
        where.setLongitude(SHOWROOM_LON);
        Map<String, Object> v = guard.reportStolen(showroom, c.getId(), where, "https://raksio.test");
        assertEquals("STOLEN", v.get("state"));
        assertEquals(1, v.get("told"));
        Incident theft = db.rows(Incident.class).get(0);
        assertEquals(IncidentType.VEHICLE_THEFT, theft.getType());
        assertEquals(ResponderTeam.POLICE, theft.getTeam());
        assertTrue(theft.getDescription().contains("https://raksio.test/car.html?t=" + c.getTrackToken()), theft.getDescription());
        assertTrue(theft.getDescription().contains("+919848011111"));
        assertFalse(alertsFor(police).isEmpty(), "the police team is told");

        List<Alert> lookout = alertsFor(ravi, CarGuardService.STOLEN_CAR);
        assertEquals(1, lookout.size());
        assertTrue(lookout.get(0).getMessage().contains("White Toyota Innova, AP 16 BX 2231, last seen 1 km from you"),
                lookout.get(0).getMessage());
        assertEquals(c.getId(), lookout.get(0).getCarId());
        assertTrue(alertsFor(farAway).isEmpty());

        // A second stolen car is a second case, not folded into the first.
        GuardedCar c2 = car(addCar(showroom, "AP 16 CC 1111", true));
        guard.reportStolen(showroom, c2.getId(), where, null);
        assertEquals(2, db.rows(Incident.class).stream().filter(i -> i.getType() == IncidentType.VEHICLE_THEFT).count());

        incidents.accept(police, theft.getId(), SHOWROOM_LAT, SHOWROOM_LON);
        fix(c, north(SHOWROOM_LAT, 3000), SHOWROOM_LON, 50.0, LocalDateTime.now());
        assertEquals(north(SHOWROOM_LAT, 3000), theft.getLatitude(), 1e-9, "the request follows the car");
        assertEquals(1, alertsFor(ravi, CarGuardService.STOLEN_CAR).stream().filter(a -> c.getId().equals(a.getCarId())).count(),
                "not told again within 30 minutes");
        assertEquals(2, alertsFor(ravi, CarGuardService.STOLEN_CAR).size(), "the second car is news");

        Map<String, Object> look = guard.lookout(ravi, c.getId());
        assertEquals(true, look.get("stolen"));
        assertEquals(Math.round(north(SHOWROOM_LAT, 3000) * 1000) / 1000.0, (double) look.get("lastLatitude"), 1e-9);

        CarPointRequest seen = new CarPointRequest();
        seen.setLatitude(north(SHOWROOM_LAT, 3100));
        seen.setLongitude(SHOWROOM_LON);
        seen.setNote("parked near the bus stand");
        assertNull(guard.sighting(ravi, c.getId(), seen).get("already"));
        assertEquals(true, guard.sighting(ravi, c.getId(), seen).get("already"));
        assertTrue(alertsFor(showroom, CarGuardService.STOLEN_SEEN).get(0).getMessage().contains("parked near the bus stand"));
        assertTrue(alertsFor(police).stream().anyMatch(a -> a.getMessage().contains(CarGuardService.STOLEN_SEEN)));

        Map<String, Object> page = guard.publicView(c.getTrackToken());
        assertEquals(false, page.get("found"));
        assertEquals("+919848011111", page.get("ownerPhone"));
        assertEquals(1, ((List<?>) page.get("trail")).size());
        assertEquals(1, ((List<?>) page.get("sightings")).size());
        assertEquals(2, guard.stolenForAdmin(admin, null).size());
        assertThrows(ApiException.class, () -> guard.stolenForAdmin(ravi, null));
        assertThrows(ApiException.class, () -> guard.delete(showroom, c.getId()));

        Map<String, Object> back = guard.recovered(showroom, c.getId(), null);
        assertEquals(1, back.get("thanked"));
        assertEquals(IncidentStatus.CANCELLED, theft.getStatus());
        assertEquals(25, users.findById(ravi.getId()).orElseThrow().getRewardPoints());
        assertFalse(alertsFor(ravi, CarGuardService.CAR_FOUND).isEmpty());
        assertTrue(alertsFor(police).stream().anyMatch(a -> a.getMessage().contains(CarGuardService.CAR_FOUND)));
        assertEquals(true, guard.publicView(c.getTrackToken()).get("found"));
        assertThrows(ApiException.class, () -> guard.sighting(ravi, c.getId(), seen));
        assertEquals(false, guard.lookout(ravi, c.getId()).get("stolen"));
    }

    @Test
    void ruleBreakerReportNamingTheStolenPlateIsASighting() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        guard.reportStolen(showroom, c.getId(), null, null); // no point given: home is used
        db.of(ViolationComplaintRepository.class).save(ViolationComplaint.builder().reporter(ravi).type(ViolationType.OVERSPEED)
                .plateNumber("ap16 bx 2231").latitude(16.52).longitude(80.63).build());
        db.of(ViolationComplaintRepository.class).save(ViolationComplaint.builder().reporter(ravi).type(ViolationType.OVERSPEED)
                .plateNumber("TS09ZZ0000").latitude(16.52).longitude(80.63).build());
        assertEquals(1, guard.plateWatch());
        assertEquals(0, guard.plateWatch(), "each report is checked once");
        CarSighting s = db.rows(CarSighting.class).get(0);
        assertEquals("REPORT", s.getSource());
        assertTrue(alertsFor(showroom, CarGuardService.STOLEN_SEEN).get(0).getMessage().contains("(overspeed)"));
        assertEquals(16.52, db.rows(Incident.class).get(0).getLatitude(), 1e-9, "no live tracker: the request moves there");
    }

    @Test
    void trackerGoingQuietIsReportedOnceWhenItMatters() {
        GuardedCar stolen = car(addCar(showroom, "AP 16 BX 2231", true));
        GuardedCar parked = car(addCar(showroom, "AP 16 CC 1111", true));
        fix(stolen, SHOWROOM_LAT, SHOWROOM_LON, 30.0, LocalDateTime.now().minusMinutes(12));
        fix(parked, SHOWROOM_LAT, SHOWROOM_LON, 0.0, LocalDateTime.now().minusMinutes(40));
        guard.reportStolen(showroom, stolen.getId(), null, null);
        assertEquals(1, guard.checkSilence());
        assertEquals(0, guard.checkSilence());
        assertTrue(alertsFor(showroom, CarGuardService.CAR_GUARD).stream().anyMatch(a -> a.getMessage().contains("stopped sending 12 min ago")));
        assertEquals("STOLEN", guard.get(showroom, stolen.getId(), null).get("state"));
        assertEquals("OFFLINE", guard.get(showroom, parked.getId(), null).get("state"));
        // It comes back: the owner hears that too.
        fix(stolen, north(SHOWROOM_LAT, 5000), SHOWROOM_LON, 20.0, LocalDateTime.now());
        assertTrue(alertsFor(showroom, CarGuardService.CAR_GUARD).stream().anyMatch(a -> a.getMessage().contains("sending again")));
    }

    // ------------------------------------------------------------------ trail, hand-over

    @Test
    void tripsAreFoundInTheTrail() {
        List<CarPosition> pts = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 11, 4, 9, 0);
        double lat = SHOWROOM_LAT;
        for (int i = 0; i < 20; i++) pts.add(pos(lat = north(lat, 100), t = t.plusSeconds(10), 36.0)); // 2 km
        for (int i = 0; i < 6; i++) pts.add(pos(lat, t = t.plusMinutes(1), 0.0));                     // 6 min stop
        for (int i = 0; i < 10; i++) pts.add(pos(lat = north(lat, 100), t = t.plusSeconds(10), 36.0)); // 1 km
        pts.add(pos(north(lat, 30), t = t.plusSeconds(5), 30.0)); // a 30 m creep: not a trip
        pts.add(pos(north(lat, 5000), t.plusHours(3), 40.0));   // 3 hours later, 5 km away: not a trip across the gap
        List<Map<String, Object>> trips = CarGuardServiceTrips.of(pts);
        assertEquals(2, trips.size(), trips.toString());
        assertEquals(1.0, (double) trips.get(0).get("distanceKm"), 0.15); // newest first
        assertEquals(2.0, (double) trips.get(1).get("distanceKm"), 0.15);
    }

    static CarPosition pos(double lat, LocalDateTime at, double kmh) {
        return CarPosition.builder().carId(1L).latitude(lat).longitude(SHOWROOM_LON).speedKmh(kmh).at(at).build();
    }

    /** tripsOf is package-private in the service package. */
    static final class CarGuardServiceTrips {
        @SuppressWarnings("unchecked")
        static List<Map<String, Object>> of(List<CarPosition> pts) {
            try {
                var m = CarGuardService.class.getDeclaredMethod("tripsOf", List.class);
                m.setAccessible(true);
                return (List<Map<String, Object>>) m.invoke(null, pts);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void soldCarIsHandedToTheBuyerWithoutTheShowroomsHistory() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        fix(c, SHOWROOM_LAT, SHOWROOM_LON, 0.0, LocalDateTime.now().minusMinutes(1));
        TestDriveRequest r = new TestDriveRequest();
        r.setCustomerName("Buyer");
        guard.startTestDrive(showroom, c.getId(), r);
        CarTransferRequest to = new CarTransferRequest();
        to.setTo("98480 33333");
        assertThrows(ApiException.class, () -> guard.transfer(showroom, c.getId(), to), "test drive still on");
        guard.endTestDrive(showroom, c.getId());
        String oldToken = c.getTrackToken();
        assertEquals("Buyer", guard.transfer(showroom, c.getId(), to).get("to"));

        assertEquals(0, ((List<?>) guard.list(showroom, null).get("cars")).size());
        Map<String, Object> mine = guard.list(buyer, null);
        assertEquals(1, ((List<?>) mine.get("cars")).size());
        assertTrue(guard.testDrives(buyer, c.getId()).isEmpty(), "the showroom's customers stay private");
        assertTrue(((List<?>) guard.trail(buyer, c.getId(), 24).get("points")).isEmpty());
        assertNotEquals(oldToken, c.getTrackToken());
        assertEquals(false, c.getGuardOn());
        assertFalse(alertsFor(buyer, CarGuardService.CAR_GUARD).isEmpty());
        // The tracker keeps working for the new owner.
        fix(c, SHOWROOM_LAT, SHOWROOM_LON, 0.0, LocalDateTime.now());
        assertEquals(true, guard.get(buyer, c.getId(), null).get("online"));
        guard.delete(buyer, c.getId());
        assertTrue(db.rows(GuardedCar.class).isEmpty());
    }

    @Test
    void ownersPhoneCanStandInForATracker() {
        GuardedCar c = car(addCar(showroom, "AP 16 BX 2231", true));
        CarPointRequest p = new CarPointRequest();
        p.setLatitude(SHOWROOM_LAT);
        p.setLongitude(SHOWROOM_LON);
        Map<String, Object> v = guard.phoneFix(showroom, c.getId(), p);
        assertEquals(true, v.get("connected"));
        assertEquals("PARKED", v.get("state"));
        assertEquals(true, v.get("atHome"));
        Map<String, Object> list = guard.list(showroom, null);
        assertEquals(1, ((Map<?, ?>) list.get("counts")).get("parked"));
    }
}
