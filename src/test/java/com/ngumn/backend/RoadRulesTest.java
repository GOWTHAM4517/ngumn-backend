package com.ngumn.backend;

import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.live.LiveDataStatusRepository;
import com.ngumn.backend.roads.*;
import com.ngumn.backend.support.InMemoryRepositories;
import com.ngumn.backend.support.OsmFixture;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ngumn.backend.support.OsmFixture.lat;
import static com.ngumn.backend.support.OsmFixture.lng;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real road rules: OpenStreetMap data in the Overpass format, the legal
 * limits of MoRTH S.O. 1522(E), posted limits, one-way roads and divided
 * highways. No network - the map comes from OsmFixture.
 */
class RoadRulesTest {

    static RoadNetwork network() throws Exception {
        return new RoadNetwork(OsmRoadParser.parse(OsmFixture.json(), LocalDateTime.now()));
    }

    @Test
    void parsesOnlyRoadsThatMatter() throws Exception {
        List<RoadSegment> roads = OsmRoadParser.parse(OsmFixture.json(), LocalDateTime.now());
        assertEquals(6, roads.size(), "untagged residential street and footway are left out");
        RoadSegment nh = roads.stream().filter(r -> r.getId() == 1002).findFirst().orElseThrow();
        assertEquals(1, nh.getOneway());
        assertTrue(nh.getDivided(), "a one-way trunk carriageway is a divided highway");
        assertEquals("NH16", nh.getRef());
        RoadSegment ring = roads.stream().filter(r -> r.getId() == 1006).findFirst().orElseThrow();
        assertEquals(60, ring.getMaxspeedKmh());
        RoadSegment lane = roads.stream().filter(r -> r.getId() == 1004).findFirst().orElseThrow();
        assertTrue(lane.getCyclesBothWays());
    }

    @Test
    void polylineRoundTrip() {
        double[] lats = {16.4990, 16.50012, 16.51234};
        double[] lngs = {80.6560, 80.65711, 80.64001};
        double[][] back = Polyline.decode(Polyline.encode(lats, lngs));
        for (int i = 0; i < 3; i++) {
            assertEquals(lats[i], back[0][i], 1e-5);
            assertEquals(lngs[i], back[1][i], 1e-5);
        }
    }

    @Test
    void readsMaxspeedValues() {
        assertEquals(50, SpeedLimitLaw.parseMaxspeed("50"));
        assertEquals(40, SpeedLimitLaw.parseMaxspeed("40 km/h"));
        assertEquals(48, SpeedLimitLaw.parseMaxspeed("30 mph"));
        assertNull(SpeedLimitLaw.parseMaxspeed("IN:urban"));
        assertNull(SpeedLimitLaw.parseMaxspeed("none"));
        assertNull(SpeedLimitLaw.parseMaxspeed("signals"));
    }

    @Test
    void legalLimitsFollowTheNotification() {
        var city = SpeedLimitLaw.RoadCategory.OTHER;
        var divided = SpeedLimitLaw.RoadCategory.DIVIDED;
        assertEquals(70, SpeedLimitLaw.legalLimit(TravelMode.CAR, city));
        assertEquals(100, SpeedLimitLaw.legalLimit(TravelMode.CAR, divided));
        assertEquals(60, SpeedLimitLaw.legalLimit(TravelMode.BIKE, city));
        assertEquals(90, SpeedLimitLaw.legalLimit(TravelMode.BUS, divided));
        assertEquals(60, SpeedLimitLaw.legalLimit(TravelMode.TRUCK, city));
        assertEquals(50, SpeedLimitLaw.legalLimit(TravelMode.AUTO, divided));
        assertNull(SpeedLimitLaw.legalLimit(TravelMode.CYCLE, city));
        assertEquals(73.5, SpeedLimitLaw.threshold(70), 1e-9, "5% tolerance");
        assertEquals(33.0, SpeedLimitLaw.threshold(30), 1e-9, "at least 3 km/h for GPS error");
    }

    @Test
    void carOnBandarRoadGetsTheCityLimit() throws Exception {
        RoadInfo r = RoadRules.at(network(), lat(3), lng(400), 90.0, 50, 8.0, TravelMode.CAR);
        assertTrue(r.isMapped());
        assertEquals("Bandar Road", r.getName());
        assertEquals(70, r.getLimitKmh());
        assertEquals("LAW", r.getLimitSource());
        assertEquals("Main road", r.getRoadType());
        assertFalse(r.isOneway());
        assertFalse(r.isWrongWay());
        RoadInfo bike = RoadRules.at(network(), lat(3), lng(400), 270.0, 50, 8.0, TravelMode.BIKE);
        assertEquals(60, bike.getLimitKmh());
    }

    @Test
    void dividedHighwayLimitsByVehicle() throws Exception {
        RoadNetwork net = network();
        RoadInfo car = RoadRules.at(net, lat(0), lng(-500), 0.0, 90, 5.0, TravelMode.CAR);
        assertEquals(1002L, car.getRoadId(), "northbound carriageway");
        assertEquals("4-lane divided road", car.getCategory());
        assertEquals(100, car.getLimitKmh());
        assertEquals("National highway", car.getRoadType());
        assertEquals(90, RoadRules.at(net, lat(0), lng(-500), 0.0, 80, 5.0, TravelMode.BUS).getLimitKmh());
        assertEquals(80, RoadRules.at(net, lat(0), lng(-500), 0.0, 70, 5.0, TravelMode.BIKE).getLimitKmh());
        assertEquals(50, RoadRules.at(net, lat(0), lng(-500), 0.0, 40, 5.0, TravelMode.AUTO).getLimitKmh());
    }

    @Test
    void headingPicksTheRightCarriageway() throws Exception {
        RoadNetwork net = network();
        // 6 m from the southbound side, 9 m from the northbound side, heading north:
        RoadInfo r = RoadRules.at(net, lat(100), lng(-491), 2.0, 80, 10.0, TravelMode.CAR);
        assertEquals(1002L, r.getRoadId(), "the side that goes north wins although the other is closer");
        assertFalse(r.isWrongWay(), "GPS drift next to the other carriageway is never wrong-way");
    }

    @Test
    void wrongWayOnARealOneWayRoad() throws Exception {
        RoadNetwork net = network();
        RoadInfo right = RoadRules.at(net, lat(401), lng(150), 90.0, 25, 5.0, TravelMode.CAR);
        assertTrue(right.isOneway());
        assertFalse(right.isWrongWay());
        RoadInfo wrong = RoadRules.at(net, lat(401), lng(150), 270.0, 25, 5.0, TravelMode.CAR);
        assertTrue(wrong.isWrongWay());
        assertEquals("Gunadala Lane", wrong.getName());
        RoadInfo cycle = RoadRules.at(net, lat(401), lng(150), 270.0, 15, 5.0, TravelMode.CYCLE);
        assertFalse(cycle.isWrongWay(), "cycles may use this lane both ways");
        RoadInfo slow = RoadRules.at(net, lat(401), lng(150), 270.0, 4, 5.0, TravelMode.CAR);
        assertFalse(slow.isWrongWay(), "not moving - no verdict");
        // On the southbound carriageway itself (1 m off), heading north: wrong way.
        RoadInfo nh = RoadRules.at(net, lat(-300), lng(-484), 0.0, 60, 4.0, TravelMode.CAR);
        assertTrue(nh.isWrongWay(), "clearly on the other side of the divided highway");
        assertEquals(1003L, nh.getRoadId());
        // Between the two sides (7 m and 8 m away): can't tell which side - no accusation.
        RoadInfo between = RoadRules.at(net, lat(-300), lng(-492), 0.0, 60, 10.0, TravelMode.CAR);
        assertFalse(between.isWrongWay());
    }

    @Test
    void postedLimitsBeatTheLawOnlyWhenLower() throws Exception {
        RoadNetwork net = network();
        RoadInfo school = RoadRules.at(net, lat(0), lng(601), 0.0, 30, 5.0, TravelMode.CAR);
        assertEquals("Patamata School Road", school.getName());
        assertEquals(40, school.getLimitKmh());
        assertEquals("SIGN", school.getLimitSource());
        RoadInfo ringAuto = RoadRules.at(net, lat(-601), lng(300), 90.0, 30, 5.0, TravelMode.AUTO);
        assertEquals(50, ringAuto.getLimitKmh(), "an auto's own limit (50) is lower than the 60 sign");
        assertEquals("LAW", ringAuto.getLimitSource());
        RoadInfo ringCar = RoadRules.at(net, lat(-601), lng(300), 90.0, 30, 5.0, TravelMode.CAR);
        assertEquals(60, ringCar.getLimitKmh());
    }

    @Test
    void offTheMappedRoadsTheGeneralLimitApplies() throws Exception {
        RoadInfo r = RoadRules.at(network(), lat(250), lng(250), 0.0, 30, 5.0, TravelMode.CAR);
        assertFalse(r.isMapped());
        assertEquals(70, r.getLimitKmh());
        RoadInfo none = RoadRules.at(RoadNetwork.EMPTY, lat(0), lng(400), 90.0, 30, 5.0, TravelMode.CAR);
        assertNull(none.getLimitKmh(), "roads not loaded: limit unknown");
    }

    @Test
    void importDownloadsSavesAndReloads() throws Exception {
        InMemoryRepositories db = new InMemoryRepositories();
        RoadSegmentRepository repo = db.of(RoadSegmentRepository.class);
        LiveDataStatusRepository statuses = db.of(LiveDataStatusRepository.class);
        RoadDataService service = new RoadDataService(repo, statuses);
        AtomicInteger calls = new AtomicInteger();
        service.useFetcher(q -> {
            calls.incrementAndGet();
            assertTrue(q.contains("out tags geom"));
            return OsmFixture.json(); // every tile answers with the same roads - kept once each
        });
        assertEquals(6, service.importNow());
        assertEquals(16, calls.get(), "4 x 4 tiles");
        assertEquals(6, repo.count());
        assertEquals("OK", statuses.findById(RoadDataService.SOURCE).orElseThrow().getState());
        assertTrue(service.loaded());

        RoadDataService fresh = new RoadDataService(repo, statuses);
        fresh.loadFromDatabase();
        assertEquals(6, fresh.network().size(), "roads come back from the database after a restart");
        assertEquals(70, fresh.roadAt(lat(3), lng(400), 90.0, 40, null, TravelMode.CAR).getLimitKmh());
    }

    @Test
    void failedDownloadKeepsTheOldRoads() throws Exception {
        InMemoryRepositories db = new InMemoryRepositories();
        RoadSegmentRepository repo = db.of(RoadSegmentRepository.class);
        LiveDataStatusRepository statuses = db.of(LiveDataStatusRepository.class);
        RoadDataService service = new RoadDataService(repo, statuses);
        service.useFetcher(q -> OsmFixture.json());
        service.importNow();
        service.useFetcher(q -> {
            throw new java.io.IOException("overpass-api.de answered 504");
        });
        assertEquals(-1, service.importNow());
        assertEquals("FAILED", statuses.findById(RoadDataService.SOURCE).orElseThrow().getState());
        assertEquals(6, repo.count(), "old roads kept");
        assertTrue(service.loaded());
    }
}
