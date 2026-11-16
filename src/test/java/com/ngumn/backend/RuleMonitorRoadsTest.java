package com.ngumn.backend;

import com.ngumn.backend.dto.LocationUpdateRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.live.LiveDataStatusRepository;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.roads.RoadDataService;
import com.ngumn.backend.roads.RoadInfo;
import com.ngumn.backend.roads.RoadSegmentRepository;
import com.ngumn.backend.service.*;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.support.OsmFixture;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static com.ngumn.backend.support.OsmFixture.lat;
import static com.ngumn.backend.support.OsmFixture.lng;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rule monitor on real roads: over-speeding against the road's own limit
 * (posted or legal, by vehicle), the people around told only when a vehicle
 * is clearly dangerous, and wrong-way driving on a mapped one-way road.
 */
class RuleMonitorRoadsTest {

    InMemoryRepositories db;
    VehicleRepository vehicles;
    TrafficViolationRepository violations;
    AlertRepository alerts;
    RuleMonitorService monitor;
    UserRepository users;

    @BeforeEach
    void setUp() throws Exception {
        db = new InMemoryRepositories();
        vehicles = db.of(VehicleRepository.class);
        violations = db.of(TrafficViolationRepository.class);
        alerts = db.of(AlertRepository.class);
        users = db.of(UserRepository.class);
        NgumnWebSocketHandler ws = new NgumnWebSocketHandler();
        AlertService alertService = new AlertService(alerts, ws, e -> { });
        TrafficRuleService rules = new TrafficRuleService(db.of(TrafficRuleZoneRepository.class),
                db.of(RoadSpeedLimitRepository.class), violations, alertService, ws);
        NearbyDangerService nearby = new NearbyDangerService(vehicles, alertService);
        RoadDataService roads = new RoadDataService(db.of(RoadSegmentRepository.class), db.of(LiveDataStatusRepository.class));
        roads.useFetcher(q -> OsmFixture.json());
        roads.importNow();
        monitor = new RuleMonitorService(rules, nearby, roads);
        set(monitor, "toleranceKmh", 5.0);
        set(monitor, "nearbyWarningSpeedKmh", 80.0);
    }

    static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    Vehicle vehicle(String name, TravelMode mode, double y, double x) {
        User u = users.save(User.builder().name(name).email(name.toLowerCase() + "@x").role(Role.DRIVER).build());
        return vehicles.save(Vehicle.builder().vehicleCode("V-" + name).owner(u).vehicleType(mode.toVehicleType())
                .travelMode(mode).currentLatitude(lat(y)).currentLongitude(lng(x))
                .lastLocationUpdate(LocalDateTime.now()).build());
    }

    /** Moves a vehicle east along Bandar Road (y=0) by 120 m at the given speed, like two GPS updates. */
    RoadInfo drive(Vehicle v, double fromY, double fromX, double toY, double toX, double kmh, double heading) {
        LocalDateTime prevTime = LocalDateTime.now().minusSeconds(5);
        v.setCurrentLatitude(lat(toY));
        v.setCurrentLongitude(lng(toX));
        v.setLastLocationUpdate(LocalDateTime.now());
        vehicles.save(v);
        LocationUpdateRequest u = new LocationUpdateRequest();
        u.setLatitude(lat(toY));
        u.setLongitude(lng(toX));
        u.setSpeedKmh(kmh);
        u.setDirectionDegrees(heading);
        u.setAccuracyMeters(6.0);
        return monitor.check(v, u, lat(fromY), lng(fromX), prevTime);
    }

    List<TrafficViolation> of(Vehicle v) {
        return violations.findAll().stream().filter(t -> t.getVehicle().getId().equals(v.getId())).toList();
    }

    @Test
    void speedingOnBandarRoadIsRecordedWithTheRoadsLimit() {
        Vehicle car = vehicle("Ravi", TravelMode.CAR, 0, 100);
        RoadInfo first = drive(car, 0, 100, 0, 200, 78, 90);
        assertEquals("Bandar Road", first.getName());
        assertEquals(70, first.getLimitKmh());
        assertTrue(of(car).isEmpty(), "one GPS update is never enough");
        drive(car, 0, 200, 0, 320, 78, 90);
        List<TrafficViolation> v = of(car);
        assertEquals(1, v.size());
        assertEquals(ViolationType.OVERSPEED, v.get(0).getType());
        assertEquals(70, v.get(0).getLimitKmh());
        assertTrue(v.get(0).getMessage().contains("where the limit is 70 km/h (Bandar Road)"), v.get(0).getMessage());
    }

    @Test
    void withinFivePercentIsNotOverSpeeding() {
        Vehicle car = vehicle("Ravi", TravelMode.CAR, 0, 100);
        drive(car, 0, 100, 0, 200, 73, 90);
        drive(car, 0, 200, 0, 320, 73, 90);
        assertTrue(of(car).isEmpty(), "73 km/h is within 5% of 70");
    }

    @Test
    void bikesHaveTheirOwnLimit() {
        Vehicle bike = vehicle("Kiran", TravelMode.BIKE, 0, 100);
        drive(bike, 0, 100, 0, 200, 66, 90);
        drive(bike, 0, 200, 0, 320, 66, 90);
        assertEquals(1, of(bike).size(), "60 for a motorcycle in the city");
        assertEquals(60, of(bike).get(0).getLimitKmh());
    }

    @Test
    void peopleAroundAreWarnedOnlyWhenItIsDangerous() {
        Vehicle ahead = vehicle("Priya", TravelMode.CAR, 0, 600); // further east on Bandar Road
        Vehicle car = vehicle("Ravi", TravelMode.CAR, 0, 100);
        drive(car, 0, 100, 0, 200, 76, 90);
        drive(car, 0, 200, 0, 320, 76, 90);
        assertEquals(1, of(car).size());
        assertTrue(alertsFor(ahead).isEmpty(), "76 in a 70 is recorded but nobody else is alarmed");

        Vehicle fast = vehicle("Teja", TravelMode.CAR, 0, 100);
        drive(fast, 0, 100, 0, 220, 92, 90);
        drive(fast, 0, 220, 0, 350, 92, 90);
        assertTrue(alertsFor(ahead).stream().anyMatch(a -> a.getMessage().startsWith("Speeding vehicle nearby")),
                "92 in a 70 is dangerous - the car ahead is warned");
    }

    @Test
    void wrongWayOnAMappedOneWayLane() {
        Vehicle car = vehicle("Ravi", TravelMode.CAR, 401, 250);
        RoadInfo r = drive(car, 401, 250, 401, 200, 25, 270);
        assertTrue(r.isWrongWay());
        drive(car, 401, 200, 401, 150, 25, 270);
        assertTrue(of(car).stream().anyMatch(t -> t.getType() == ViolationType.WRONG_WAY
                && "Gunadala Lane".equals(t.getZoneName())));
    }

    @Test
    void lorryOnARoadClosedToHeavyVehicles() {
        Vehicle lorry = vehicle("Suresh", TravelMode.TRUCK, 801, 650);
        drive(lorry, 801, 650, 801, 750, 25, 90);
        drive(lorry, 801, 750, 801, 850, 25, 90);
        assertTrue(of(lorry).stream().anyMatch(t -> t.getType() == ViolationType.NO_ENTRY
                && t.getMessage().startsWith("No entry: Market Road - No heavy vehicles")), "" + of(lorry));
        Vehicle car = vehicle("Ravi", TravelMode.CAR, 801, 650);
        drive(car, 801, 650, 801, 750, 25, 90);
        drive(car, 801, 750, 801, 850, 25, 90);
        assertTrue(of(car).isEmpty(), "cars may use Market Road");
    }

    @Test
    void walkingIsNeverChecked() {
        Vehicle walker = vehicle("Anu", TravelMode.WALK, 401, 250);
        drive(walker, 401, 250, 401, 200, 25, 270);
        drive(walker, 401, 200, 401, 150, 25, 270);
        assertTrue(of(walker).isEmpty());
    }

    List<Alert> alertsFor(Vehicle v) {
        return alerts.findAll().stream().filter(a -> a.getUser() != null && a.getUser().getId().equals(v.getOwner().getId())).toList();
    }
}
