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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Buy &amp; Sell: listings for owners, dealers and showrooms, the stolen-plate
 * check, finding vehicles, the price guide, test drives from request to
 * ratings, reminders, selling (with the tracker), reports and photos - the
 * real services against in-memory repositories:
 *
 *   ./mvnw -Dtest='MarketTest' test
 */
class MarketTest {

    static final double BENZ_LAT = 16.5062, BENZ_LON = 80.6480; // Benz Circle, Vijayawada

    InMemoryRepositories db;
    UserRepository users;
    VehicleRepository vehicles;
    IncidentService incidents;
    MarketService market;
    MarketArea area;
    MarketRoadService road;
    MarketOfferService offers;
    CarGuardService guard;
    User owner, dealer, showroom, buyer, buyer2, noPhone, police, admin, r1, r2;

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
        area = new MarketArea();
        road = new MarketRoadService(db.of(MarketListingRepository.class), db.of(MarketWishRepository.class),
                db.of(ListingSightingRepository.class), vehicles, alerts, area);
        market = new MarketService(db.of(MarketListingRepository.class), db.of(ListingPhotoRepository.class),
                db.of(TestDriveBookingRepository.class), db.of(ListingReportRepository.class), db.of(GuardedCarRepository.class),
                db.of(CarPositionRepository.class), db.of(CarSightingRepository.class), users, alerts, incidents, ws,
                db.of(MarketOfferRepository.class), road, area);
        offers = new MarketOfferService(db.of(MarketListingRepository.class), db.of(MarketOfferRepository.class), market, alerts, ws);
        guard = new CarGuardService(db.of(GuardedCarRepository.class), db.of(CarPositionRepository.class), market,
                db.of(CarSightingRepository.class), db.of(ViolationComplaintRepository.class), vehicles, users, alerts, incidents, rewards, ws);

        owner = person("Ravi Owner", "+919848010001");
        dealer = person("Sai Motors Dealer", "+919848010002");
        showroom = person("Vijay U-Trust", "+919848010003");
        buyer = person("Priya Buyer", "+919848010004");
        buyer2 = person("Kiran Buyer", "+919848010005");
        noPhone = person("No Phone", null);
        r1 = person("Reporter One", "+919848010006");
        r2 = person("Reporter Two", "+919848010007");
        police = users.save(User.builder().name("Ramesh").email("ramesh@x").role(Role.DRIVER).responderTeam(ResponderTeam.POLICE)
                .unitName("Traffic Police - Benz Circle").rewardPoints(0).build());
        admin = users.save(User.builder().name("Admin").email("admin@x").role(Role.ADMIN).rewardPoints(0).build());
    }

    User person(String name, String phone) {
        return users.save(User.builder().name(name).email(name.toLowerCase().replaceAll("[^a-z]", "") + "@x").role(Role.DRIVER)
                .phone(phone).rewardPoints(0).createdAt(LocalDateTime.now().minusDays(40)).build());
    }

    static double north(double lat, double meters) {
        return lat + meters / 111_320.0;
    }

    ListingRequest swift(String plate, long price) {
        ListingRequest r = new ListingRequest();
        r.setSellerType("OWNER");
        r.setVehicleType("CAR");
        r.setMake("Maruti Suzuki");
        r.setModel("Swift");
        r.setVariant("VXi");
        r.setYear(2019);
        r.setFuel("PETROL");
        r.setTransmission("MANUAL");
        r.setKmDriven(42_000);
        r.setOwnerCount(1);
        r.setColour("Red");
        r.setPlate(plate);
        r.setPrice(price);
        r.setLatitude(BENZ_LAT);
        r.setLongitude(BENZ_LON);
        r.setArea("Benz Circle, Vijayawada");
        r.setRcInHand(true);
        r.setInsuranceValid(true);
        return r;
    }

    MarketListing listing(Map<String, Object> view) {
        return db.rows(MarketListing.class).stream().filter(l -> l.getId().equals(view.get("id"))).findFirst().orElseThrow();
    }

    TestDriveBooking booking(Map<String, Object> view) {
        return db.rows(TestDriveBooking.class).stream().filter(b -> b.getId().equals(view.get("id"))).findFirst().orElseThrow();
    }

    List<Alert> alertsFor(User u, String headline) {
        return db.rows(Alert.class).stream().filter(a -> a.getUser() != null && a.getUser().getId().equals(u.getId())
                && a.getMessage().startsWith(headline + ":")).toList();
    }

    static LocalDateTime tomorrowAt(int h, int m) {
        return LocalDate.now().plusDays(1).atTime(LocalTime.of(h, m));
    }

    BookingRequest ask(LocalDateTime at, boolean licence) {
        BookingRequest r = new BookingRequest();
        r.setMeetAt(at);
        r.setDriverLicence(licence);
        r.setNote("Can I bring my mechanic?");
        return r;
    }

    BookingActionRequest action() {
        return new BookingActionRequest();
    }

    CarPointRequest at(double lat, double lon, double kmh) {
        CarPointRequest p = new CarPointRequest();
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setSpeedKmh(kmh);
        return p;
    }

    /** Moves the agreed time to now, as if they'd met. */
    void meetNow(Map<String, Object> view) {
        booking(view).setMeetAt(LocalDateTime.now().plusMinutes(5));
    }

    // ------------------------------------------------------------------ listings

    @Test
    void sellingNeedsAMobileAndAPlateWhichBuyersOnlyPartlySee() {
        assertThrows(ApiException.class, () -> market.create(noPhone, swift("AP 16 BX 2231", 450_000)));
        ListingRequest noPlate = swift(null, 450_000);
        assertThrows(ApiException.class, () -> market.create(owner, noPlate), "a used vehicle needs its plate");
        ListingRequest dealerNoName = swift("AP 16 CC 1111", 500_000);
        dealerNoName.setSellerType("DEALER");
        assertThrows(ApiException.class, () -> market.create(dealer, dealerNoName), "a dealer gives the business name");

        Map<String, Object> v = market.create(owner, swift("ap 16 bx-2231", 450_000));
        assertEquals("2019 Maruti Suzuki Swift VXi", v.get("title"));
        assertEquals("AP 16 BX-2231", v.get("plate"), "the seller sees it in full");
        assertEquals(true, v.get("plateChecked"));
        assertEquals(true, v.get("mine"));

        Map<String, Object> seen = market.detail(buyer, (Long) v.get("id"));
        assertEquals("AP16 •• 2231", seen.get("plate"));
        assertEquals(true, seen.get("plateHidden"));
        assertNull(seen.get("sellerPhone"), "no number before a test drive is agreed");
        assertEquals(1, listing(v).getViews());
        Map<?, ?> trust = (Map<?, ?>) seen.get("seller");
        assertEquals("Ravi Owner", trust.get("name"));
        assertEquals(true, trust.get("phoneSaved"));

        // One plate, one listing - by anyone.
        assertThrows(ApiException.class, () -> market.create(dealer, swift("AP16BX2231", 400_000)));
        assertThrows(ApiException.class, () -> market.create(owner, swift("AP16BX2231", 400_000)));
        // An owner sells at most 3 at a time.
        market.create(owner, swift("AP 16 AA 0002", 350_000));
        market.create(owner, swift("AP 16 AA 0003", 300_000));
        ApiException more = assertThrows(ApiException.class, () -> market.create(owner, swift("AP 16 AA 0004", 300_000)));
        assertTrue(more.getMessage().contains("3 vehicles"), more.getMessage());
    }

    @Test
    void aStolenPlateCantBeSoldAndItsOwnerAndThePoliceHearWhoTried() {
        CarRequest cr = new CarRequest();
        cr.setName("My Innova");
        cr.setPlate("AP 16 ZZ 9999");
        cr.setModel("Toyota Innova");
        cr.setHomeLatitude(BENZ_LAT);
        cr.setHomeLongitude(BENZ_LON);
        Map<String, Object> carView = guard.add(owner, cr, null);
        GuardedCar car = db.rows(GuardedCar.class).get(0);
        // It's for sale first...
        ListingRequest inn = swift("AP 16 ZZ 9999", 1_200_000);
        inn.setMake("Toyota");
        inn.setModel("Innova");
        Map<String, Object> l = market.create(owner, inn);
        assertEquals(1, ((List<?>) market.browse(buyer, filters(null)).get("listings")).size());
        // ...then reported stolen: the listing stops showing, and says so to anyone who opens it.
        guard.reportStolen(owner, car.getId(), null, null);
        Incident theft = db.rows(Incident.class).get(0);
        incidents.accept(police, theft.getId(), BENZ_LAT, BENZ_LON);
        assertTrue(((List<?>) market.browse(buyer, filters(null)).get("listings")).isEmpty());
        assertEquals("WITHDRAWN", listing(l).getStatus(), "taken off sale when reported stolen");

        // Someone else tries to sell it.
        ListingRequest fake = swift("ap16zz9999", 600_000);
        fake.setLatitude(north(BENZ_LAT, 4000));
        fake.setArea("Gollapudi");
        ApiException no = assertThrows(ApiException.class, () -> market.create(dealer, fakeAsDealer(fake)));
        assertTrue(no.getMessage().contains("reported stolen"), no.getMessage());
        CarSighting s = db.rows(CarSighting.class).stream().filter(x -> "MARKET".equals(x.getSource())).findFirst().orElseThrow();
        assertEquals(dealer.getId(), s.getReporter().getId());
        assertTrue(alertsFor(owner, CarGuardService.STOLEN_SEEN).get(0).getMessage().contains("in Gollapudi"));
        assertTrue(db.rows(Alert.class).stream().anyMatch(a -> a.getUser().getId().equals(police.getId())
                && a.getMessage().contains("Sai Motors Dealer (+919848010002)")), "the police get the seller's name and number");
        assertEquals(north(BENZ_LAT, 4000), theft.getLatitude(), 1e-9, "no live tracker: the request moves there");
        // The owner can't relist it until it's found.
        assertThrows(ApiException.class, () -> market.create(owner, inn));

        Map<String, Object> check = market.checkPlate(buyer, "AP-16-ZZ-9999");
        assertEquals(true, check.get("stolen"));
        assertEquals(false, market.checkPlate(buyer, "AP 16 BX 2231").get("stolen"));
        assertNotNull(carView);
    }

    ListingRequest fakeAsDealer(ListingRequest r) {
        r.setSellerType("DEALER");
        r.setBusinessName("Sai Motors");
        return r;
    }

    MarketService.Filters filters(String q) {
        return new MarketService.Filters(BENZ_LAT, BENZ_LON, null, null, q, null, null, null, null, null, null, null, null, null, 0, null);
    }

    @Test
    void buyersFindVehiclesByWhatTheyWantAndSeeAPriceGuide() {
        market.create(owner, swift("AP 16 AA 0001", 450_000));
        ListingRequest d = swift("AP 16 AA 0002", 520_000);
        d.setSellerType("DEALER");
        d.setBusinessName("Sai Motors");
        d.setYear(2020);
        d.setLatitude(north(BENZ_LAT, 3000));
        Map<String, Object> dealerSwift = market.create(dealer, d);
        ListingRequest bike = swift("AP 16 AA 0003", 65_000);
        bike.setVehicleType("BIKE");
        bike.setMake("Honda");
        bike.setModel("Shine");
        bike.setLatitude(north(BENZ_LAT, 1000));
        market.create(buyer2, bike);
        ListingRequest far = swift("AP 16 AA 0004", 400_000);
        far.setLatitude(north(BENZ_LAT, 80_000)); // 80 km away: outside the Vijayawada area
        ApiException outside = assertThrows(ApiException.class, () -> market.create(r1, far));
        assertTrue(outside.getMessage().contains("Vijayawada area") && outside.getMessage().contains("40 km outside"), outside.getMessage());
        ListingRequest guntur = swift("AP 16 AA 0004", 400_000);
        guntur.setLatitude(16.3067);  // Guntur, ~31 km: inside
        guntur.setLongitude(80.4365);
        guntur.setArea("Brodipet, Guntur");
        market.create(r1, guntur);
        ListingRequest creta = swift(null, 1_450_000);
        creta.setSellerType("SHOWROOM");
        creta.setBusinessName("Lakshmi Hyundai");
        creta.setCondition("NEW");
        creta.setMake("Hyundai");
        creta.setModel("Creta");
        creta.setYear(2026);
        creta.setKmDriven(null);
        Map<String, Object> newCreta = market.create(showroom, creta);
        assertEquals(0, newCreta.get("kmDriven"));

        Map<String, Object> all = market.browse(buyer, filters(null));
        assertEquals(5, all.get("total"), "the whole area, Guntur too");
        assertEquals(true, all.get("inArea"));
        assertEquals("Vijayawada area", ((Map<?, ?>) all.get("area")).get("name"));
        assertEquals(4, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, 10.0, null, null, null, null, null, null, null, null, null, null, null, 0, null)).get("total"), "within 10 km");
        Map<String, Object> fromHyderabad = market.browse(buyer, new MarketService.Filters(17.385, 78.4867, 10.0, null, null, null, null, null, null, null, null, null, null, null, 0, null));
        assertEquals(false, fromHyderabad.get("inArea"));
        assertEquals(5, fromHyderabad.get("total"), "outside the area: the whole area, no distances");
        List<Map<String, Object>> near = list(all);
        assertEquals(0.0, (double) near.get(0).get("distanceKm"), 0.05, "nearest first");

        assertEquals(1, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, 0.0, "BIKE", null, null, null, null, null, null, null, null, null, null, 0, null)).get("total"));
        assertEquals(3, market.browse(buyer, filters("swift")).get("total"), "Guntur's too: the whole area");
        assertEquals(1, market.browse(buyer, filters("sai motors")).get("total"));
        Map<String, Object> cheap = market.browse(buyer, new MarketService.Filters(null, null, null, "CAR", null, null, 500_000L, null, null, null, null, null, null, "PRICE_LOW", 0, null));
        assertEquals(List.of(400_000L, 450_000L), list(cheap).stream().map(x -> x.get("price")).toList(), "anywhere when no place is given");
        assertEquals(2, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, null, null, null, null, null, "BUSINESS", null, null, null, null, null, null, 0, null)).get("total"));
        assertEquals(1, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, null, null, null, null, null, null, null, null, null, null, "NEW", null, 0, null)).get("total"));
        assertEquals(1, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, null, "CAR", null, null, null, null, null, null, 2020, null, "USED", null, 0, null)).get("total"));

        // Three similar Swifts (2019-2020) are for sale: the dealer's 5.2 lakh is on the high side of 4-4.5 lakh.
        Map<?, ?> guide = (Map<?, ?>) market.detail(buyer, (Long) dealerSwift.get("id")).get("priceGuide");
        assertEquals(2, guide.get("count"));
        assertEquals(400_000L, guide.get("low"));
        assertEquals(450_000L, guide.get("high"));
        assertEquals("HIGH", guide.get("verdict"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("listings");
    }

    // ------------------------------------------------------------------ test drives

    @Test
    void testDriveFromRequestToRatings() {
        Map<String, Object> l = market.create(owner, swift("AP 16 BX 2231", 450_000));
        Long id = (Long) l.get("id");
        assertThrows(ApiException.class, () -> market.book(noPhone, id, ask(tomorrowAt(10, 30), true)), "the buyer needs a mobile saved");
        assertThrows(ApiException.class, () -> market.book(owner, id, ask(tomorrowAt(10, 30), true)), "not your own");
        assertThrows(ApiException.class, () -> market.book(buyer, id, ask(LocalDateTime.now().plusMinutes(10), true)), "30 minutes' notice");
        assertThrows(ApiException.class, () -> market.book(buyer, id, ask(tomorrowAt(21, 0), true)), "7 am to 8 pm");
        assertThrows(ApiException.class, () -> market.book(buyer, id, ask(LocalDateTime.now().plusDays(20).withHour(11), true)), "two weeks");

        Map<String, Object> b = market.book(buyer, id, ask(tomorrowAt(10, 30), true));
        assertEquals("REQUESTED", b.get("status"));
        assertEquals("SELLER", b.get("waitingFor"));
        Alert req = alertsFor(owner, MarketService.DRIVE_REQUEST).get(0);
        assertTrue(req.getMessage().contains("Priya Buyer wants to test-drive your 2019 Maruti Suzuki Swift VXi tomorrow at 10:30 am"), req.getMessage());
        assertEquals(b.get("id"), req.getBookingId());
        assertEquals(id, req.getListingId());
        assertThrows(ApiException.class, () -> market.book(buyer, id, ask(tomorrowAt(15, 0), true)), "one at a time per vehicle");
        assertNull(b.get("otherPhone"));
        Long bid = (Long) b.get("id");

        // The seller offers another time; the buyer agrees.
        BookingActionRequest other = action();
        other.setAt(tomorrowAt(16, 0));
        Map<String, Object> proposed = market.propose(owner, bid, other);
        assertEquals("BUYER", proposed.get("waitingFor"));
        assertTrue(alertsFor(buyer, MarketService.TEST_DRIVE).get(0).getMessage().contains("tomorrow at 4:00 pm"));
        assertThrows(ApiException.class, () -> market.agree(owner, bid), "only the buyer agrees");
        Map<String, Object> agreed = market.agree(buyer, bid);
        assertEquals("ACCEPTED", agreed.get("status"));
        String code = (String) agreed.get("meetCode");
        assertEquals(4, code.length());
        assertEquals("+919848010001", agreed.get("otherPhone"), "numbers are shared once it's agreed");
        Map<String, Object> sellerSees = market.booking(owner, bid);
        assertNull(sellerSees.get("meetCode"), "only the buyer's phone shows the code");
        assertEquals("+919848010004", sellerSees.get("otherPhone"));
        assertEquals("AP 16 BX 2231", market.detail(buyer, id).get("plate"), "and the full plate");

        // Another buyer asks for the same slot: the seller can't say yes to both.
        Map<String, Object> b2 = market.book(buyer2, id, ask(tomorrowAt(16, 15), false));
        assertThrows(ApiException.class, () -> market.accept(owner, (Long) b2.get("id")));
        market.decline(owner, (Long) b2.get("id"), action());
        assertEquals("DECLINED", booking(b2).getStatus());

        // They meet.
        BookingActionRequest start = action();
        start.setCode(code);
        start.setLatitude(BENZ_LAT);
        start.setLongitude(BENZ_LON);
        assertThrows(ApiException.class, () -> market.start(owner, bid, start), "it's for tomorrow");
        meetNow(agreed);
        BookingActionRequest wrong = action();
        wrong.setCode(code.equals("0000") ? "1111" : "0000");
        assertThrows(ApiException.class, () -> market.start(owner, bid, wrong));
        assertThrows(ApiException.class, () -> market.start(buyer, bid, start), "the seller starts it");
        Map<String, Object> going = market.start(owner, bid, start);
        assertEquals("ACTIVE", going.get("status"));
        assertEquals("PHONE", going.get("trackedBy"));
        assertEquals(80, going.get("speedLimitKmh"));
        assertTrue(alertsFor(buyer, MarketService.TEST_DRIVE).stream().anyMatch(a -> a.getMessage().contains("started - back by")));

        // The phones in the car follow it.
        LocalDateTime t0 = LocalDateTime.now();
        TestDriveBooking row = booking(going);
        drive(row, BENZ_LAT, 0.0, t0.plusSeconds(3));
        drive(row, north(BENZ_LAT, 1500), 95.0, t0.plusSeconds(60));
        drive(row, north(BENZ_LAT, 5500), 70.0, t0.plusSeconds(240)); // beyond 5 km
        drive(row, north(BENZ_LAT, 2000), 60.0, t0.plusSeconds(400));
        drive(row, north(BENZ_LAT, 30), 0.0, t0.plusSeconds(520));
        drive(row, north(BENZ_LAT, 35), 0.0, t0.plusSeconds(600));
        assertEquals("ACTIVE", row.getStatus());
        drive(row, north(BENZ_LAT, 32), 0.0, t0.plusSeconds(660)); // parked 2+ minutes at the start
        assertEquals("ENDED", row.getStatus(), "back and parked: it ends by itself");
        List<Alert> told = alertsFor(owner, MarketService.TEST_DRIVE);
        assertTrue(told.stream().anyMatch(a -> a.getMessage().contains("Priya Buyer is doing 95 km/h")), told.toString());
        assertTrue(told.stream().anyMatch(a -> a.getMessage().contains("from the start (allowed 5 km)")));
        assertTrue(told.stream().anyMatch(a -> a.getMessage().contains("is back with")));
        Map<String, Object> done = market.booking(owner, bid);
        assertEquals("RISKY", done.get("verdict"));
        assertEquals(true, done.get("canRate"));

        // Ratings: each side once; what the other said shows after you've rated.
        BookingActionRequest five = action();
        five.setStars(5);
        five.setAsDescribed(true);
        five.setComment("Exactly as described");
        Map<String, Object> rated = market.rate(buyer, bid, five);
        assertEquals(5, rated.get("myStars"));
        assertNull(rated.get("theirStars"));
        assertThrows(ApiException.class, () -> market.rate(buyer, bid, five));
        BookingActionRequest three = action();
        three.setStars(3);
        three.setComment("Drove too fast");
        assertEquals(5, market.rate(owner, bid, three).get("theirStars"));
        Map<?, ?> trust = (Map<?, ?>) market.detail(buyer2, id).get("seller");
        assertEquals(5.0, trust.get("rating"));
        assertEquals(100L, trust.get("asDescribedPct"));
        assertEquals(1L, trust.get("drives"));
        Map<?, ?> asBuyer = (Map<?, ?>) market.booking(owner, bid).get("other");
        assertEquals(3.0, asBuyer.get("rating"));
    }

    void drive(TestDriveBooking b, double lat, double kmh, LocalDateTime at) {
        market.phoneFixAt(b, lat, BENZ_LON, kmh, at);
    }

    @Test
    void remindersExpiryAndLateDrives() {
        Map<String, Object> l = market.create(owner, swift("AP 16 BX 2231", 450_000));
        Long id = (Long) l.get("id");
        Map<String, Object> asked = market.book(buyer, id, ask(tomorrowAt(10, 0), true));
        Map<String, Object> agreed = market.book(buyer2, id, ask(tomorrowAt(14, 0), true));
        market.accept(owner, (Long) agreed.get("id"));
        booking(asked).setMeetAt(LocalDateTime.now().minusMinutes(1));       // never answered
        booking(agreed).setMeetAt(LocalDateTime.now().plusMinutes(20));      // in 20 minutes
        assertEquals(1 + 2, market.checkBookings(), "the unanswered buyer, and both sides of the one coming up");
        assertEquals("EXPIRED", booking(asked).getStatus());
        assertEquals(0, market.checkBookings(), "once");
        assertTrue(alertsFor(owner, MarketService.TEST_DRIVE).get(0).getMessage().contains("Kiran Buyer is coming at"));

        // A walk-in customer at the dealer, late back.
        BookingRequest walk = new BookingRequest();
        walk.setName("Walk-in Wasim");
        walk.setPhone("98480 55555");
        walk.setMinutes(15);
        Map<String, Object> w = market.walkIn(owner, id, walk);
        assertEquals("ACTIVE", w.get("status"));
        assertEquals("+919848055555", w.get("otherPhone"));
        assertThrows(ApiException.class, () -> market.walkIn(owner, id, walk), "one drive at a time");
        booking(w).setDueBackAt(LocalDateTime.now().minusMinutes(1));
        assertEquals(1, market.checkBookings());
        assertTrue(alertsFor(owner, MarketService.TEST_DRIVE).stream().anyMatch(a -> a.getMessage().contains("Walk-in Wasim should have been back")
                && a.getMessage().contains("+919848055555")));
        assertEquals("CHECK", market.end(owner, (Long) w.get("id")).get("verdict"));
        // Agreed but nobody started it, hours later: it didn't happen.
        booking(agreed).setMeetAt(LocalDateTime.now().minusHours(4));
        market.checkBookings();
        assertEquals("EXPIRED", booking(agreed).getStatus());
        assertEquals(1L, ((Map<?, ?>) market.booking(owner, (Long) agreed.get("id")).get("other")).get("missed"));
    }

    @Test
    void aTrackerFollowsTheDriveAndTheSoldCarGoesToItsBuyer() {
        CarRequest cr = new CarRequest();
        cr.setName("U-Trust stock 12");
        cr.setPlate("AP 16 BX 2231");
        cr.setModel("Toyota Innova");
        cr.setHomeLatitude(BENZ_LAT);
        cr.setHomeLongitude(BENZ_LON);
        guard.add(showroom, cr, null);
        GuardedCar car = db.rows(GuardedCar.class).get(0);
        guard.ingest(new CarGuardService.Fix(car.getTrackerId(), BENZ_LAT, BENZ_LON, 0.0, null, null, null, LocalDateTime.now().minusSeconds(30), null));

        ListingRequest r = swift(null, 1_250_000);
        r.setSellerType("SHOWROOM");
        r.setBusinessName("U-Trust, Lakshmi Toyota");
        r.setMake("Toyota");
        r.setModel("Innova");
        r.setCarId(car.getId());
        Map<String, Object> l = market.create(showroom, r);
        assertEquals("AP 16 BX 2231", l.get("plate"), "the plate comes from Car guard");
        assertEquals(true, l.get("tracked"));
        assertEquals(l.get("id"), ((Map<?, ?>) guard.get(showroom, car.getId(), null).get("listing")).get("id"));
        Long id = (Long) l.get("id");

        Map<String, Object> b = market.book(buyer, id, ask(tomorrowAt(11, 0), true));
        Map<String, Object> other = market.book(buyer2, id, ask(tomorrowAt(15, 0), true));
        Map<String, Object> ok = market.accept(showroom, (Long) b.get("id"));
        String code = (String) market.booking(buyer, (Long) b.get("id")).get("meetCode");
        meetNow(ok);
        BookingActionRequest s = action();
        s.setCode(code);
        Map<String, Object> going = market.start(showroom, (Long) b.get("id"), s);
        assertEquals("TRACKER", going.get("trackedBy"));
        assertEquals("TEST_DRIVE", guard.get(showroom, car.getId(), null).get("state"));

        LocalDateTime t = LocalDateTime.now().plusSeconds(3);
        guard.ingest(new CarGuardService.Fix(car.getTrackerId(), north(BENZ_LAT, 800), BENZ_LON, 50.0, null, null, null, t, null));
        guard.ingest(new CarGuardService.Fix(car.getTrackerId(), north(BENZ_LAT, 1800), BENZ_LON, 55.0, null, null, null, t.plusSeconds(60), null));
        // The phones' positions don't count when the tracker is following.
        market.phoneFix(buyer, (Long) b.get("id"), at(north(BENZ_LAT, 9000), BENZ_LON, 120));
        TestDriveBooking row = booking(going);
        assertEquals(55.0, row.getMaxSpeedKmh(), 0.01);
        assertFalse(row.getLeftArea());
        assertEquals(1.8, (double) market.booking(showroom, row.getId()).get("distanceKm"), 0.05);
        assertThrows(ApiException.class, () -> market.markSold(showroom, id, new SoldRequest()), "end the drive first");
        market.end(buyer, row.getId());

        SoldRequest sold = new SoldRequest();
        sold.setBookingId(row.getId());
        sold.setPrice(1_200_000L);
        sold.setHandOverTracker(true);
        Map<String, Object> done = market.markSold(showroom, id, sold);
        assertEquals("SOLD", done.get("status"));
        assertEquals(true, done.get("handedOver"));
        assertEquals("Priya Buyer", done.get("soldTo"));
        assertEquals(buyer.getId(), car.getOwner().getId(), "the car and its tracker are the buyer's now");
        assertEquals("CANCELLED", booking(other).getStatus(), "the other buyer's test drive is off");
        assertTrue(alertsFor(buyer2, MarketService.TEST_DRIVE).stream().anyMatch(a -> a.getMessage().contains("it has been sold")));
        assertTrue(alertsFor(buyer, MarketService.MARKET).get(0).getMessage().contains("as sold to you, with its GPS tracker"));
        assertTrue(guard.testDrives(buyer, car.getId()).isEmpty(), "the showroom's customers stay private");
        assertTrue(list(market.browse(buyer2, filters(null))).isEmpty());
        assertEquals("SOLD", market.detail(buyer, id).get("status"), "the buyer can still open it");
        assertThrows(ApiException.class, () -> market.detail(buyer2, id), "others can't");
        assertEquals(1L, ((Map<?, ?>) market.detail(buyer, id).get("seller")).get("sales"));
    }

    @Test
    void threeReportsHideAListingUntilTheAdminLooks() {
        Map<String, Object> l = market.create(owner, swift("AP 16 BX 2231", 45_000));
        Long id = (Long) l.get("id");
        ListingReportRequest scam = new ListingReportRequest();
        scam.setReason("scam");
        scam.setNote("Asked for an advance on UPI");
        assertThrows(ApiException.class, () -> market.report(owner, id, scam));
        market.report(buyer, id, scam);
        assertEquals(true, market.report(buyer, id, scam).get("already"));
        market.report(buyer2, id, scam);
        assertEquals("ACTIVE", listing(l).getStatus());
        assertEquals(true, market.report(r1, id, scam).get("hidden"));
        assertEquals("HIDDEN", listing(l).getStatus());
        assertTrue(list(market.browse(r2, filters(null))).isEmpty());
        assertTrue(alertsFor(owner, MarketService.MARKET).get(0).getMessage().contains("hidden for now"));

        assertThrows(ApiException.class, () -> market.reportedForAdmin(buyer));
        List<Map<String, Object>> rep = market.reportedForAdmin(admin);
        assertEquals(1, rep.size());
        assertEquals(3, ((List<?>) rep.get(0).get("reports")).size());
        assertEquals("AP 16 BX 2231", rep.get(0).get("plate"));
        market.adminAction(admin, id, "REMOVE");
        assertEquals("WITHDRAWN", listing(l).getStatus());
    }

    @Test
    void photosAreSmallImagesWithUnguessableLinks() {
        Map<String, Object> l = market.create(owner, swift("AP 16 BX 2231", 450_000));
        Long id = (Long) l.get("id");
        byte[] jpeg = new byte[2000];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        PhotoRequest p = new PhotoRequest();
        p.setData("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
        Map<String, Object> v = market.addPhoto(owner, id, p);
        List<?> urls = (List<?>) v.get("photos");
        assertEquals(1, urls.size());
        String token = urls.get(0).toString().replace("/api/market/photo/", "");
        assertEquals(24, token.length());
        assertEquals("image/jpeg", market.photo(token).getContentType());
        assertEquals(urls.get(0), market.browse(buyer, filters(null)).get("listings") instanceof List<?> ls
                ? ((Map<?, ?>) ls.get(0)).get("photo") : null);

        PhotoRequest text = new PhotoRequest();
        text.setData(Base64.getEncoder().encodeToString("hello, not a photo".getBytes()));
        assertThrows(ApiException.class, () -> market.addPhoto(owner, id, text));
        assertThrows(ApiException.class, () -> market.addPhoto(buyer, id, p), "only the seller");
        for (int i = 0; i < 5; i++) market.addPhoto(owner, id, p);
        assertThrows(ApiException.class, () -> market.addPhoto(owner, id, p), "six at most");
        market.removePhoto(owner, id, token);
        assertThrows(ApiException.class, () -> market.photo(token));
        assertEquals(5, ((List<?>) market.detail(owner, id).get("photos")).size());
    }
}
