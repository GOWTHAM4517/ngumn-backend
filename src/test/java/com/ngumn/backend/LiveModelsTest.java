package com.ngumn.backend;

import com.ngumn.backend.entity.*;
import com.ngumn.backend.live.LiveDataStatusRepository;
import com.ngumn.backend.live.WeatherService;
import com.ngumn.backend.ml.MlModelStateRepository;
import com.ngumn.backend.ml.ReportTrustModel;
import com.ngumn.backend.ml.TrafficSpeedModel;
import com.ngumn.backend.ml.TrafficSpeedStatRepository;
import com.ngumn.backend.repository.AlertRepository;
import com.ngumn.backend.repository.UserRepository;
import com.ngumn.backend.repository.VehicleRepository;
import com.ngumn.backend.roads.RoadDataService;
import com.ngumn.backend.roads.RoadInfo;
import com.ngumn.backend.roads.RoadSegmentRepository;
import com.ngumn.backend.service.AlertService;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.support.OsmFixture;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.ngumn.backend.support.OsmFixture.lat;
import static com.ngumn.backend.support.OsmFixture.lng;
import static org.junit.jupiter.api.Assertions.*;

/** The two learned models (report trust, road speed / jams) and the live weather warnings. */
class LiveModelsTest {

    InMemoryRepositories db;
    VehicleRepository vehicles;
    UserRepository users;
    AlertRepository alerts;
    AlertService alertService;
    RoadDataService roads;
    NgumnWebSocketHandler ws;

    @BeforeEach
    void setUp() {
        db = new InMemoryRepositories();
        vehicles = db.of(VehicleRepository.class);
        users = db.of(UserRepository.class);
        alerts = db.of(AlertRepository.class);
        ws = new NgumnWebSocketHandler();
        alertService = new AlertService(alerts, ws, e -> { });
        roads = new RoadDataService(db.of(RoadSegmentRepository.class), db.of(LiveDataStatusRepository.class));
        roads.useFetcher(q -> OsmFixture.json());
        roads.importNow();
    }

    Vehicle vehicle(String name, double y, double x, double kmh, double heading) {
        User u = users.save(User.builder().name(name).email(name.toLowerCase() + "@x").role(Role.DRIVER).build());
        return vehicles.save(Vehicle.builder().vehicleCode("V-" + name).owner(u).vehicleType(VehicleType.CAR)
                .travelMode(TravelMode.CAR).currentLatitude(lat(y)).currentLongitude(lng(x)).speedKmh(kmh)
                .directionDegrees(heading).lastLocationUpdate(LocalDateTime.now()).build());
    }

    // ------------------------------------------------------------------ report trust

    @Test
    void trustModelStartsSensibleAndLearnsFromOutcomes() {
        MlModelStateRepository repo = db.of(MlModelStateRepository.class);
        ReportTrustModel m = new ReportTrustModel(repo);
        var trusted = new ReportTrustModel.Report(90, 12, 0, 2, 10, ReportTrustModel.Kind.POTHOLE);
        var doubtful = new ReportTrustModel.Report(20, 0, 5, 0, 2, ReportTrustModel.Kind.ACCIDENT);
        assertTrue(m.percent(trusted) > 80, "a trusted reporter, seen by others: likely true");
        assertTrue(m.percent(doubtful) < 40, "a reporter with removed reports, alone: doubtful");

        // A world where night-time accident reports by new people are mostly false and potholes mostly true.
        Random rnd = new Random(7);
        int correctLate = 0;
        for (int i = 0; i < 2000; i++) {
            int trust = 30 + rnd.nextInt(60);
            int hour = rnd.nextInt(24);
            ReportTrustModel.Kind kind = ReportTrustModel.Kind.values()[rnd.nextInt(5)];
            int same = rnd.nextInt(3);
            double z = -0.2 + 2.0 * (trust - 50) / 50.0 + 1.2 * Math.log1p(same)
                    + (kind == ReportTrustModel.Kind.POTHOLE ? 1.5 : 0)
                    + (kind == ReportTrustModel.Kind.ACCIDENT && (hour < 5 || hour > 22) ? -2.5 : 0);
            boolean truth = rnd.nextDouble() < 1 / (1 + Math.exp(-z));
            var r = new ReportTrustModel.Report(trust, rnd.nextInt(4), rnd.nextInt(2), same, hour, kind);
            if (i >= 1500 && (m.percent(r) >= 50) == truth) correctLate++;
            m.learn(r, truth);
        }
        assertEquals(2000, m.samples());
        assertTrue(correctLate / 500.0 > 0.70, "accuracy on new reports after learning: " + correctLate / 500.0);
        @SuppressWarnings("unchecked")
        Map<String, Double> w = (Map<String, Double>) m.summary().get("weights");
        assertTrue(w.get("kind_pothole") > w.get("kind_accident"), "learned that pothole reports are more reliable");

        ReportTrustModel reloaded = new ReportTrustModel(repo);
        reloaded.load();
        assertEquals(2000, reloaded.samples(), "what it learned survives a restart");
        assertEquals(m.percent(trusted), reloaded.percent(trusted));
    }

    // ------------------------------------------------------------------ traffic model

    TrafficSpeedModel traffic() {
        return new TrafficSpeedModel(roads, db.of(TrafficSpeedStatRepository.class), vehicles, alertService, ws);
    }

    RoadInfo bandar(double x, double heading) {
        return roads.roadAt(lat(2), lng(x), heading, 30, 5.0, TravelMode.CAR);
    }

    @Test
    void learnsTheNormalSpeedOfARoadForEachHour() {
        TrafficSpeedModel model = traffic();
        Vehicle car = vehicle("Ravi", 2, 300, 34, 90);
        LocalDateTime tuesday10 = LocalDateTime.of(2026, 11, 17, 10, 15);
        LocalDateTime tuesday18 = LocalDateTime.of(2026, 11, 17, 18, 15);
        long t = 1_000_000;
        for (int i = 0; i < 60; i++) {
            t += 30_000;
            assertTrue(model.observe(car, bandar(300, 90), 34 + (i % 3), tuesday10, t));
            t += 30_000;
            model.observe(car, bandar(300, 90), 7 + (i % 3), tuesday18, t);
        }
        List<Map<String, Object>> evening = model.forecastNear(lat(0), lng(400), 2, tuesday18.plusWeeks(1));
        assertEquals(1, evening.size(), "Bandar Road is usually slow on Tuesday evening");
        assertEquals("Bandar Road", evening.get(0).get("name"));
        assertTrue(((Number) evening.get(0).get("speedKmh")).doubleValue() < 12);
        assertTrue(model.forecastNear(lat(0), lng(400), 2, tuesday10.plusWeeks(1)).isEmpty(), "not at 10 am");
        Map<String, Object> s = model.summary();
        assertEquals(120L, s.get("samples"));
        assertTrue(((Number) s.get("improvementPercent")).doubleValue() > 30,
                "learned speeds beat the fixed starting guess: " + s);
    }

    @Test
    void parkedCarsAreNotTraffic() {
        TrafficSpeedModel model = traffic();
        Vehicle parked = vehicle("Anil", 2, 300, 0, 90);
        assertFalse(model.observe(parked, bandar(300, 90), 0, LocalDateTime.now(), 5_000_000L),
                "standing without having moved in the last 3 minutes = parked");
        assertTrue(model.observe(parked, bandar(300, 90), 25, LocalDateTime.now(), 5_030_000L));
        assertTrue(model.observe(parked, bandar(310, 90), 0, LocalDateTime.now(), 5_060_000L),
                "stopped right after moving = stuck in traffic");
        assertFalse(model.observe(parked, bandar(310, 90), 0, LocalDateTime.now(), 5_065_000L),
                "same road again within 20 s - not another sample");
    }

    @Test
    void detectsALiveJamAndWarnsDriversHeadingIntoIt() {
        TrafficSpeedModel model = traffic();
        long now = System.currentTimeMillis();
        LocalDateTime at = LocalDateTime.now();
        Vehicle a = vehicle("A", 2, 500, 5, 90);
        Vehicle b = vehicle("B", 2, 520, 4, 90);
        Vehicle c = vehicle("C", 2, 540, 6, 90);
        for (Vehicle v : List.of(a, b, c)) {
            model.observe(v, bandar(500, 90), 30, at, now - 120_000); // were moving
            model.observe(v, bandar(510, 90), v == b ? 4 : 5, at, now - 30_000);
        }
        Vehicle coming = vehicle("Priya", 2, -400, 40, 90);  // 900 m west, heading east into it
        Vehicle leaving = vehicle("Teja", 2, -400, 40, 270); // same place, heading away
        List<TrafficSpeedModel.Jam> jams = model.detect(now);
        assertEquals(1, jams.size());
        assertEquals(TrafficSpeedModel.Level.JAM, jams.get(0).level());
        assertEquals(3, jams.get(0).vehicles());
        assertTrue(alertsFor(coming).stream().anyMatch(x -> x.getMessage().startsWith("Traffic jam ahead: Bandar Road")));
        assertTrue(alertsFor(leaving).isEmpty());
        model.detect(now + 60_000);
        assertEquals(1, alertsFor(coming).size(), "told once, not every minute");
        assertEquals(1, model.liveNear(lat(0), lng(500), 2).size());
        assertNotNull(model.liveNear(lat(0), lng(500), 2).get(0).get("polyline"), "the map can draw it");
        assertTrue(model.detect(now + 6 * 60_000).isEmpty(), "readings older than 5 minutes: jam cleared");
    }

    List<Alert> alertsFor(Vehicle v) {
        return alerts.findAll().stream().filter(x -> x.getUser() != null && x.getUser().getId().equals(v.getOwner().getId())).toList();
    }

    // ------------------------------------------------------------------ weather

    static String place(double temp, double rain, int code, double gusts, double[] hourlyRain, int[] hourlyCode) {
        StringBuilder hp = new StringBuilder();
        StringBuilder hc = new StringBuilder();
        for (int i = 0; i < hourlyRain.length; i++) {
            if (i > 0) {
                hp.append(',');
                hc.append(',');
            }
            hp.append(hourlyRain[i]);
            hc.append(hourlyCode[i]);
        }
        return "{\"latitude\":16.5,\"longitude\":80.6,\"current\":{\"time\":\"2026-11-16T15:00\",\"temperature_2m\":" + temp
                + ",\"precipitation\":" + rain + ",\"weather_code\":" + code + ",\"wind_gusts_10m\":" + gusts + "},"
                + "\"hourly\":{\"precipitation\":[" + hp + "],\"weather_code\":[" + hc + "]}}";
    }

    @Test
    void weatherWarningsReachPeopleInThatTownOnce() {
        String json = "[" + String.join(",",
                place(29, 0.4, 3, 18, new double[]{0.4, 3.0, 6.0, 1.0}, new int[]{3, 61, 95, 61}), // Vijayawada: storm coming
                place(30, 9.2, 65, 22, new double[]{9.2, 5.0, 1.0, 0}, new int[]{65, 63, 61, 3}),  // Guntur: heavy rain now
                place(31, 0, 1, 10, new double[]{0, 0, 0, 0}, new int[]{1, 1, 1, 1}),             // Gannavaram: fine
                place(41.5, 0, 0, 12, new double[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0}),           // Amaravati: heat
                place(24, 0, 45, 5, new double[]{0, 0, 0, 0}, new int[]{45, 45, 3, 3}),           // Mangalagiri: fog
                place(30, 0, 2, 55, new double[]{0, 0, 0, 0}, new int[]{2, 2, 2, 2})) + "]";      // Tenali: wind
        LiveDataStatusRepository statuses = db.of(LiveDataStatusRepository.class);
        WeatherService weather = new WeatherService(statuses, vehicles, alertService, ws);
        weather.useFetcher(url -> {
            assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=16.5062,16.3067"));
            return json;
        });
        Vehicle inVja = vehicle("Ravi", 0, 0, 30, 90);          // Benz Circle, Vijayawada
        assertEquals(6, weather.refresh(System.currentTimeMillis()));
        assertEquals("OK", statuses.findById(WeatherService.SOURCE).orElseThrow().getState());

        WeatherService.Reading vja = weather.near(lat(0), lng(0));
        assertEquals("Vijayawada", vja.place().name());
        assertEquals("THUNDERSTORM", vja.warnings().get(0).kind());
        assertTrue(weather.near(16.30, 80.44).warnings().stream().anyMatch(w -> w.kind().equals("HEAVY_RAIN")));
        assertTrue(weather.near(16.51, 80.51).warnings().stream().anyMatch(w -> w.kind().equals("HEAT")));
        assertTrue(weather.near(16.43, 80.55).warnings().stream().anyMatch(w -> w.kind().equals("FOG")));
        assertTrue(weather.near(16.24, 80.64).warnings().stream().anyMatch(w -> w.kind().equals("WIND")));
        assertTrue(weather.near(16.54, 80.80).warnings().isEmpty());

        List<Alert> told = alertsFor(inVja);
        assertEquals(1, told.size(), "only Vijayawada's warning, not Guntur's: " + told);
        assertTrue(told.get(0).getMessage().startsWith("Weather warning: Vijayawada: thunderstorm expected"));
        weather.refresh(System.currentTimeMillis() + 60_000);
        assertEquals(1, alertsFor(inVja).size(), "not again within 3 hours");

        weather.useFetcher(url -> {
            throw new java.io.IOException("Open-Meteo answered 503");
        });
        assertEquals(0, weather.refresh(System.currentTimeMillis()));
        assertEquals("FAILED", statuses.findById(WeatherService.SOURCE).orElseThrow().getState());
        assertNotNull(weather.near(lat(0), lng(0)), "last good reading kept");
    }
}
