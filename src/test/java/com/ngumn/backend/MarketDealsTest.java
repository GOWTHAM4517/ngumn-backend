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
 * Buy &amp; Sell in the city: "Looking for", vehicles for sale on the road
 * next to you, passing a dealer, and offers - a price, or your vehicle in
 * exchange at a showroom - through to the sale and the exchanged vehicle
 * going into the showroom's stock:
 *
 *   ./mvnw -Dtest='MarketDealsTest' test
 */
class MarketDealsTest {

    static final double BENZ_LAT = 16.5062, BENZ_LON = 80.6480; // Benz Circle, Vijayawada

    InMemoryRepositories db;
    UserRepository users;
    VehicleRepository vehicles;
    IncidentService incidents;
    MarketArea area;
    MarketRoadService road;
    MarketService market;
    MarketOfferService offers;
    CarGuardService guard;
    User owner, dealer, showroom, buyer, buyer2, buyer3;

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
        buyer3 = person("Anil Buyer", "+919848010006");
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
        return r;
    }

    ListingRequest bike(String plate, long price, String model) {
        ListingRequest r = swift(plate, price);
        r.setVehicleType("BIKE");
        r.setMake("Honda");
        r.setModel(model);
        r.setVariant(null);
        r.setColour("Black");
        return r;
    }

    ListingRequest creta() {
        ListingRequest r = swift(null, 1_450_000);
        r.setSellerType("SHOWROOM");
        r.setBusinessName("Lakshmi Hyundai");
        r.setCondition("NEW");
        r.setMake("Hyundai");
        r.setModel("Creta");
        r.setVariant("SX");
        r.setYear(2026);
        r.setKmDriven(null);
        r.setColour("White");
        return r;
    }

    WishRequest wish(String type, String q, Long maxPrice) {
        WishRequest w = new WishRequest();
        w.setVehicleType(type);
        w.setQ(q);
        w.setMaxPrice(maxPrice);
        return w;
    }

    /** Someone's Raksio vehicle, its position updated just now. */
    Vehicle drive(User u, String plate, double lat, double lon, double kmh) {
        Vehicle v = db.rows(Vehicle.class).stream().filter(x -> x.getOwner().getId().equals(u.getId())).findFirst()
                .orElseGet(() -> Vehicle.builder().vehicleCode("U" + u.getId()).owner(u).vehicleType(VehicleType.CAR).build());
        v.setPlateNumber(plate);
        v.setCurrentLatitude(lat);
        v.setCurrentLongitude(lon);
        v.setSpeedKmh(kmh);
        v.setLastLocationUpdate(LocalDateTime.now());
        return vehicles.save(v);
    }

    MarketListing listing(Map<String, Object> view) {
        return db.rows(MarketListing.class).stream().filter(l -> l.getId().equals(view.get("id"))).findFirst().orElseThrow();
    }

    MarketOffer offer(Map<String, Object> view) {
        return db.rows(MarketOffer.class).stream().filter(o -> o.getId().equals(view.get("id"))).findFirst().orElseThrow();
    }

    List<Alert> alertsFor(User u, String headline) {
        return db.rows(Alert.class).stream().filter(a -> a.getUser() != null && a.getUser().getId().equals(u.getId())
                && a.getMessage().startsWith(headline + ":")).toList();
    }

    OfferRequest price(long p) {
        OfferRequest r = new OfferRequest();
        r.setKind("PRICE");
        r.setPrice(p);
        return r;
    }

    OfferRequest value(Long v) {
        OfferRequest r = new OfferRequest();
        r.setValue(v);
        return r;
    }

    // ------------------------------------------------------------------ Looking for

    @Test
    void lookingForTellsBuyersAboutNewVehiclesLikeIt() {
        assertThrows(ApiException.class, () -> road.addWish(buyer, new WishRequest()), "say what you're looking for");
        Map<String, Object> w = road.addWish(buyer, wish("CAR", null, 600_000L));
        assertEquals("car under ₹6 lakh", w.get("label"));
        assertEquals(0L, w.get("forSaleNow"));
        assertEquals(true, w.get("onRoad"));
        assertEquals(true, w.get("newListings"));
        WishRequest quiet = wish("CAR", null, null);
        quiet.setNewListings(false);
        road.addWish(buyer2, quiet);
        road.addWish(owner, wish(null, "swift", null));

        Map<String, Object> s = market.create(owner, swift("AP 16 BX 2231", 450_000));
        market.create(showroom, creta());
        List<Alert> told = alertsFor(buyer, MarketRoadService.LOOKING);
        assertEquals(1, told.size(), "the Swift, not the 14.5 lakh Creta");
        assertEquals("Looking for: a 2019 Maruti Suzuki Swift VXi is now for sale - ₹4.5 lakh, Benz Circle, Vijayawada. It matches \"car under ₹6 lakh\".",
                told.get(0).getMessage());
        assertEquals(s.get("id"), told.get(0).getListingId());
        assertTrue(alertsFor(buyer2, MarketRoadService.LOOKING).isEmpty(), "asked not to be told about new ones");
        assertTrue(alertsFor(owner, MarketRoadService.LOOKING).isEmpty(), "not about your own");
        assertEquals(1L, road.myWishes(buyer).get(0).get("forSaleNow"));
        assertEquals(1L, ((Map<?, ?>) market.detail(owner, (Long) s.get("id")).get("reach")).get("matched"));

        WishRequest diesel = wish(null, "swift", 500_000L);
        diesel.setFuel("diesel");
        diesel.setMinYear(2018);
        diesel.setSellerType("BUSINESS");
        assertEquals("diesel swift under ₹5 lakh, 2018 or newer, from a dealer or showroom", road.addWish(buyer3, diesel).get("label"));
        WishRequest bad = wish("TRUCK", null, null);
        assertThrows(ApiException.class, () -> road.addWish(buyer3, bad));
        for (int i = 0; i < 3; i++) road.addWish(buyer, wish("BIKE", null, 100_000L + i));
        road.addWish(buyer, wish("SCOOTER", null, null));
        ApiException six = assertThrows(ApiException.class, () -> road.addWish(buyer, wish("AUTO", null, null)));
        assertTrue(six.getMessage().contains("5 things"), six.getMessage());
        Long first = (Long) road.myWishes(buyer).get(0).get("id");
        assertThrows(ApiException.class, () -> road.deleteWish(buyer2, first), "only your own");
        road.deleteWish(buyer, first);
        assertEquals(4, road.myWishes(buyer).size());
    }

    @Test
    void buyAndSellStaysInTheCity() {
        ListingRequest hyd = swift("AP 16 BX 2231", 450_000);
        hyd.setLatitude(17.385);
        hyd.setLongitude(78.4867);
        ApiException e = assertThrows(ApiException.class, () -> market.create(owner, hyd));
        assertTrue(e.getMessage().startsWith("Buy & Sell covers the Vijayawada area (Vijayawada, Gannavaram, Amaravati, Mangalagiri, Guntur, Tenali)"),
                e.getMessage());
        ListingRequest gannavaram = swift("AP 16 BX 2231", 450_000);
        gannavaram.setLatitude(16.5410);  // Gannavaram, ~17 km
        gannavaram.setLongitude(80.8020);
        Map<String, Object> l = market.create(owner, gannavaram);
        ListingRequest move = new ListingRequest();
        move.setLatitude(16.9891);         // Rajahmundry: too far
        move.setLongitude(81.7840);
        assertThrows(ApiException.class, () -> market.update(owner, (Long) l.get("id"), move));
        assertEquals(40.0, area.radiusKm());
        assertTrue(area.contains(16.4300, 80.5680), "Mangalagiri");
        assertTrue(area.contains(16.5150, 80.5160), "Amaravati");
    }

    // ------------------------------------------------------------------ on the road

    @Test
    void aVehicleForSaleOnTheRoadIsShownToBuyersNextToIt() {
        Map<String, Object> s = market.create(owner, swift("AP 16 BX 2231", 450_000));
        road.addWish(buyer, wish(null, "swift", null));
        road.addWish(buyer2, wish(null, "swift", null));
        WishRequest notOnRoad = wish(null, "swift", null);
        notOnRoad.setOnRoad(false);
        road.addWish(buyer3, notOnRoad);

        // At the signal: the buyer is 80 m behind, buyer2 is 1 km away, buyer3 is right there but said no.
        drive(buyer, null, BENZ_LAT, BENZ_LON, 0);
        drive(buyer2, null, north(BENZ_LAT, 1000), BENZ_LON, 30);
        drive(buyer3, null, north(BENZ_LAT, 40), BENZ_LON, 0);
        // The seller parked at home: not announced.
        road.vehicleMoved(drive(owner, "AP16BX2231", north(BENZ_LAT, 80), BENZ_LON, 0));
        assertTrue(alertsFor(buyer, MarketRoadService.NEAR).isEmpty(), "standing still at home isn't 'on the road'");
        // The seller drives off in it.
        road.vehicleMoved(drive(owner, "AP 16 BX 2231", north(BENZ_LAT, 80), BENZ_LON, 32));
        List<Alert> told = alertsFor(buyer, MarketRoadService.NEAR);
        assertEquals(1, told.size());
        assertEquals("For sale nearby: the red 2019 Maruti Suzuki Swift VXi (AP16 •• 2231) on the road next to you is for sale - ₹4.5 lakh."
                + " Have a look once you've stopped.", told.get(0).getMessage());
        assertEquals(s.get("id"), told.get(0).getListingId());
        assertTrue(alertsFor(buyer2, MarketRoadService.NEAR).isEmpty(), "1 km away");
        assertTrue(alertsFor(buyer3, MarketRoadService.NEAR).isEmpty(), "doesn't want road notes");
        // Stopped at the next signal a moment later: still on the road - but nobody is told twice in a week.
        road.vehicleMoved(drive(owner, "AP 16 BX 2231", north(BENZ_LAT, 100), BENZ_LON, 0));
        assertEquals(1, alertsFor(buyer, MarketRoadService.NEAR).size());

        // The other way round: buyer2 drives up next to it while it's on the road.
        road.vehicleMoved(drive(buyer2, null, north(BENZ_LAT, 160), BENZ_LON, 25));
        assertEquals(1, alertsFor(buyer2, MarketRoadService.NEAR).size());

        // The seller sees how many people looking for one were told.
        Map<?, ?> reach = (Map<?, ?>) market.detail(owner, (Long) s.get("id")).get("reach");
        assertEquals(2L, reach.get("road"));
        assertNull(market.detail(buyer, (Long) s.get("id")).get("reach"), "only the seller sees it");
        assertNull(market.detail(buyer, (Long) s.get("id")).get("roadLatitude"), "where it was on the road is never shown");

        // Someone else driving a car with that plate isn't the seller: nothing.
        MarketListing l = listing(s);
        l.setRoadAt(null);
        drive(buyer3, null, BENZ_LAT, BENZ_LON, 0);
        road.updateWish(buyer3, (Long) road.myWishes(buyer3).get(0).get("id"), onRoadOn());
        road.vehicleMoved(drive(dealer, "AP16BX2231", north(BENZ_LAT, 50), BENZ_LON, 30));
        assertTrue(alertsFor(buyer3, MarketRoadService.NEAR).isEmpty());
        // A seller who turned it off isn't announced.
        ListingRequest off = new ListingRequest();
        off.setShowOnRoad(false);
        market.update(owner, l.getId(), off);
        road.vehicleMoved(drive(owner, "AP 16 BX 2231", north(BENZ_LAT, 50), BENZ_LON, 30));
        assertTrue(alertsFor(buyer3, MarketRoadService.NEAR).isEmpty());
    }

    WishRequest onRoadOn() {
        WishRequest w = new WishRequest();
        w.setOnRoad(true);
        return w;
    }

    @Test
    void aCarGuardTrackerInACarForSaleWorksToo() {
        CarRequest cr = new CarRequest();
        cr.setName("My Swift");
        cr.setPlate("AP 16 BX 2231");
        cr.setModel("Maruti Swift");
        cr.setHomeLatitude(BENZ_LAT);
        cr.setHomeLongitude(BENZ_LON);
        guard.add(owner, cr, null);
        GuardedCar car = db.rows(GuardedCar.class).get(0);
        ListingRequest r = swift("AP 16 BX 2231", 450_000);
        r.setCarId(car.getId());
        Map<String, Object> s = market.create(owner, r);
        road.addWish(buyer, wish("CAR", null, 500_000L));
        drive(buyer, null, north(BENZ_LAT, 2000), BENZ_LON, 0);
        // Not a test drive: the owner's family is out in it, the tracker says where.
        market.carFix(car.getId(), north(BENZ_LAT, 2100), BENZ_LON, 40.0, LocalDateTime.now(), true);
        assertEquals(1, alertsFor(buyer, MarketRoadService.NEAR).size());
        assertEquals(s.get("id"), alertsFor(buyer, MarketRoadService.NEAR).get(0).getListingId());
        // An old fix (the tracker catching up) isn't "now".
        road.addWish(buyer2, wish("CAR", null, 500_000L));
        drive(buyer2, null, north(BENZ_LAT, 3000), BENZ_LON, 0);
        market.carFix(car.getId(), north(BENZ_LAT, 3050), BENZ_LON, 40.0, LocalDateTime.now().minusMinutes(10), true);
        assertTrue(alertsFor(buyer2, MarketRoadService.NEAR).isEmpty());
    }

    @Test
    void passingADealerTellsOncePerShopAndNeverAboutAPrivateHome() {
        ListingRequest shine = bike("AP 16 DD 0001", 62_000, "Shine");
        shine.setSellerType("DEALER");
        shine.setBusinessName("Sai Motors");
        shine.setArea("Eluru Road");
        ListingRequest unicorn = bike("AP 16 DD 0002", 85_000, "Unicorn");
        unicorn.setSellerType("DEALER");
        unicorn.setBusinessName("Sai Motors");
        Map<String, Object> first = market.create(dealer, shine);
        market.create(dealer, unicorn);
        ListingRequest home = bike("AP 16 DD 0003", 55_000, "Activa");
        home.setLatitude(north(BENZ_LAT, 100));
        market.create(owner, home);
        road.addWish(buyer, wish("BIKE", null, 100_000L));

        road.vehicleMoved(drive(buyer, null, north(BENZ_LAT, 150), BENZ_LON, 35));
        List<Alert> told = alertsFor(buyer, MarketRoadService.NEAR);
        assertEquals(1, told.size(), "one note for the shop, nothing about the owner's home");
        assertEquals("For sale nearby: Sai Motors, 150 m from you, has 2 vehicles you're looking for, from ₹62,000. Have a look once you've stopped.",
                told.get(0).getMessage());
        assertEquals(first.get("id"), told.get(0).getListingId(), "opens the cheapest");
        // Passing again (on the way back): not again this week.
        road.vehicleMoved(drive(buyer, null, north(BENZ_LAT, 120), BENZ_LON, 35));
        assertEquals(1, alertsFor(buyer, MarketRoadService.NEAR).size());
        assertEquals(1L, ((Map<?, ?>) market.detail(dealer, (Long) first.get("id")).get("reach")).get("place"));
    }

    @Test
    void nobodyGetsMoreThanFiveADay() {
        road.addWish(buyer, wish("CAR", null, null));
        for (int i = 0; i < 7; i++) {
            ListingRequest r = swift("AP 16 EE 00" + (10 + i), 300_000 + i * 10_000L);
            r.setLatitude(north(BENZ_LAT, 1000 * i));
            market.create(i < 3 ? owner : i < 6 ? dealer : showroom, i < 3 ? r : asDealer(r));
        }
        assertEquals(5, alertsFor(buyer, MarketRoadService.LOOKING).size());
    }

    ListingRequest asDealer(ListingRequest r) {
        r.setSellerType("DEALER");
        r.setBusinessName("Sai Motors");
        return r;
    }

    // ------------------------------------------------------------------ offers

    @Test
    void aPriceOfferIsCounteredAgreedReservedAndSold() {
        Map<String, Object> s = market.create(owner, swift("AP 16 BX 2231", 450_000));
        Long id = (Long) s.get("id");
        assertThrows(ApiException.class, () -> offers.make(buyer, id, price(500_000)), "more than asking");
        ApiException low = assertThrows(ApiException.class, () -> offers.make(buyer, id, price(200_000)));
        assertTrue(low.getMessage().contains("half"), low.getMessage());
        assertThrows(ApiException.class, () -> offers.make(owner, id, price(400_000)), "your own");
        OfferRequest swap = new OfferRequest();
        swap.setKind("EXCHANGE");
        assertThrows(ApiException.class, () -> offers.make(buyer, id, swap), "an owner doesn't take exchange unless they say so");

        Map<String, Object> o = offers.make(buyer, id, price(400_000));
        assertEquals("OPEN", o.get("status"));
        assertEquals("SELLER", o.get("waitingFor"));
        assertNull(o.get("sellerPhone"), "no numbers before a deal");
        assertEquals("Offer: Priya Buyer offers ₹4,00,000 for your 2019 Maruti Suzuki Swift VXi (asking ₹4,50,000).",
                alertsFor(owner, MarketOfferService.OFFER).get(0).getMessage());
        assertThrows(ApiException.class, () -> offers.make(buyer, id, price(410_000)), "one at a time");
        Map<String, Object> o2 = offers.make(buyer2, id, price(420_000));

        assertThrows(ApiException.class, () -> offers.answer(owner, (Long) o.get("id"), "counter", value(390_000L)), "below their offer");
        assertThrows(ApiException.class, () -> offers.answer(buyer, (Long) o.get("id"), "accept", null), "only the seller");
        Map<String, Object> c = offers.answer(owner, (Long) o.get("id"), "counter", value(430_000L));
        assertEquals("COUNTERED", c.get("status"));
        assertTrue(alertsFor(buyer, MarketOfferService.OFFER).get(0).getMessage().contains("Ravi Owner would take ₹4,30,000"));
        Map<?, ?> forBuyer = (Map<?, ?>) market.detail(buyer, id).get("myOffer");
        assertEquals("BUYER", forBuyer.get("waitingFor"));
        assertEquals(false, market.detail(buyer, id).get("canOffer"), "an offer is open");

        Map<String, Object> agreed = offers.buyerAct(buyer, (Long) o.get("id"), "agree");
        assertEquals("ACCEPTED", agreed.get("status"));
        assertEquals(430_000L, agreed.get("youPay"));
        assertEquals("+919848010001", agreed.get("sellerPhone"), "a deal: they can call each other");
        assertEquals("RESERVED", listing(s).getStatus());
        // Reserved: only the buyer with the deal can book a test drive; the seller can't just put it back on sale.
        BookingRequest br = new BookingRequest();
        br.setMeetAt(LocalDate.now().plusDays(1).atTime(LocalTime.of(10, 30)));
        br.setDriverLicence(true);
        ApiException taken = assertThrows(ApiException.class, () -> market.book(buyer3, id, br));
        assertTrue(taken.getMessage().contains("reserved"), taken.getMessage());
        assertEquals(true, market.detail(buyer, id).get("canBook"));
        assertEquals(false, market.detail(buyer3, id).get("canBook"));
        market.book(buyer, id, br);
        assertThrows(ApiException.class, () -> market.setStatus(owner, id, "ACTIVE"));
        assertThrows(ApiException.class, () -> offers.make(buyer3, id, price(440_000)), "reserved");

        Map<?, ?> mine = market.mine(owner);
        List<?> shown = (List<?>) mine.get("offers");
        assertEquals(2, shown.size());
        assertEquals("OPEN", ((Map<?, ?>) shown.get(0)).get("status"), "the one waiting for the seller comes first");
        assertEquals(1L, ((Map<?, ?>) ((List<?>) mine.get("listings")).get(0)).get("offersWaiting"), "Kiran's is still open");

        SoldRequest sold = new SoldRequest();
        sold.setOfferId((Long) o2.get("id"));
        assertThrows(ApiException.class, () -> market.markSold(owner, id, sold), "accept it first");
        sold.setOfferId((Long) o.get("id"));
        market.markSold(owner, id, sold);
        MarketListing l = listing(s);
        assertEquals("SOLD", l.getStatus());
        assertEquals(buyer.getId(), l.getSoldTo().getId());
        assertEquals(430_000L, l.getSoldPrice());
        assertEquals("DONE", offer(o).getStatus());
        assertEquals("CLOSED", offer(o2).getStatus());
        assertTrue(alertsFor(buyer2, MarketOfferService.OFFER).stream().anyMatch(a -> a.getMessage().contains("sold")));
    }

    @Test
    void anExchangeAtAShowroomIsCheckedValuedSoldAndGoesIntoItsStock() {
        Map<String, Object> cretaView = market.create(showroom, creta());
        Long id = (Long) cretaView.get("id");
        assertEquals(true, cretaView.get("exchange"), "showrooms take exchange");
        assertEquals(false, market.create(owner, swift("AP 16 AB 1000", 300_000)).get("exchange"), "owners don't, unless they say so");
        assertEquals(1, market.browse(buyer, new MarketService.Filters(BENZ_LAT, BENZ_LON, null, null, null, null, null, null, null, null,
                null, null, null, null, 0, true)).get("total"));

        // A stolen car can't be traded in: its owner and the police hear who tried.
        CarRequest cr = new CarRequest();
        cr.setName("My Innova");
        cr.setPlate("AP 16 ZZ 9999");
        cr.setModel("Toyota Innova");
        cr.setHomeLatitude(BENZ_LAT);
        cr.setHomeLongitude(BENZ_LON);
        guard.add(owner, cr, null);
        guard.reportStolen(owner, db.rows(GuardedCar.class).get(0).getId(), null, null);
        OfferRequest hot = new OfferRequest();
        hot.setKind("EXCHANGE");
        hot.setExVehicleType("CAR");
        hot.setExMake("Toyota");
        hot.setExModel("Innova");
        hot.setExYear(2017);
        hot.setExPlate("ap16zz9999");
        ApiException no = assertThrows(ApiException.class, () -> offers.make(buyer2, id, hot));
        assertTrue(no.getMessage().contains("reported stolen in Raksio, so it can't be traded in"), no.getMessage());
        assertTrue(alertsFor(owner, CarGuardService.STOLEN_SEEN).get(0).getMessage().contains("trade in your My Innova"));
        assertTrue(db.rows(MarketOffer.class).isEmpty());
        OfferRequest noPlate = new OfferRequest();
        noPlate.setKind("EXCHANGE");
        noPlate.setExVehicleType("CAR");
        noPlate.setExMake("Maruti Suzuki");
        noPlate.setExModel("Swift");
        noPlate.setExYear(2019);
        assertThrows(ApiException.class, () -> offers.make(buyer2, id, noPlate), "the plate is needed for the check");

        // The buyer offers their own Swift, which they had up for sale.
        Map<String, Object> mySwift = market.create(buyer, swift("AP 16 CD 1234", 450_000));
        OfferRequest ex = new OfferRequest();
        ex.setKind("EXCHANGE");
        ex.setExListingId((Long) mySwift.get("id"));
        ex.setExExpected(440_000L);
        Map<String, Object> o = offers.make(buyer, id, ex);
        assertEquals("Offer: Priya Buyer offers their 2019 Maruti Suzuki Swift VXi (42,000 km) in exchange for your 2026 Hyundai Creta SX"
                + " - they hope to get ₹4,40,000 for it.", alertsFor(showroom, MarketOfferService.OFFER).get(0).getMessage());
        Map<?, ?> seen = (Map<?, ?>) ((List<?>) market.detail(showroom, id).get("offers")).get(0);
        assertEquals("AP16 •• 1234", ((Map<?, ?>) seen.get("exchange")).get("plate"), "the full number once there's a deal");

        assertThrows(ApiException.class, () -> offers.answer(showroom, (Long) o.get("id"), "accept", null), "what is it worth to them?");
        assertThrows(ApiException.class, () -> offers.answer(showroom, (Long) o.get("id"), "accept", value(1_500_000L)));
        Map<String, Object> a = offers.answer(showroom, (Long) o.get("id"), "accept", value(420_000L));
        assertEquals("ACCEPTED", a.get("status"));
        assertEquals(1_030_000L, a.get("youPay"));
        assertEquals("AP 16 CD 1234", ((Map<?, ?>) a.get("exchange")).get("plate"));
        assertTrue(alertsFor(buyer, MarketOfferService.OFFER).get(0).getMessage()
                .startsWith("Offer: Lakshmi Hyundai accepted - your 2019 Maruti Suzuki Swift VXi at ₹4,20,000 - you pay ₹10,30,000 for the 2026 Hyundai Creta SX."));

        SoldRequest sold = new SoldRequest();
        sold.setOfferId((Long) o.get("id"));
        Map<String, Object> done = market.markSold(showroom, id, sold);
        Map<?, ?> deal = (Map<?, ?>) done.get("exchangeOffer");
        assertEquals("DONE", deal.get("status"));
        assertEquals(true, deal.get("canAddToStock"));
        assertEquals("WITHDRAWN", listing(mySwift).getStatus(), "the buyer's own listing of it comes down");
        assertEquals(1_450_000L, listing(cretaView).getSoldPrice());

        assertThrows(ApiException.class, () -> offers.toStock(owner, (Long) o.get("id"), null));
        Map<String, Object> stock = offers.toStock(showroom, (Long) o.get("id"), null);
        assertEquals("2019 Maruti Suzuki Swift VXi", stock.get("title"));
        assertEquals(470_000L, stock.get("price"), "what they gave plus a margin, rounded");
        assertEquals("SHOWROOM", stock.get("sellerType"));
        assertEquals("AP 16 CD 1234", stock.get("plate"));
        assertEquals(2, stock.get("ownerCount"), "one more owner now");
        assertEquals(true, stock.get("exchange"));
        assertThrows(ApiException.class, () -> offers.toStock(showroom, (Long) o.get("id"), null), "once");
        assertEquals(false, ((Map<?, ?>) ((List<?>) market.detail(showroom, id).get("offers")).get(0)).get("canAddToStock"));
    }

    @Test
    void aBuyerCanWithdrawAndASellerCanCallOffADeal() {
        Map<String, Object> s = market.create(owner, swift("AP 16 BX 2231", 450_000));
        Long id = (Long) s.get("id");
        Map<String, Object> o = offers.make(buyer, id, price(430_000));
        offers.answer(owner, (Long) o.get("id"), "accept", null);
        assertEquals("RESERVED", listing(s).getStatus());
        offers.answer(owner, (Long) o.get("id"), "decline", null);
        assertEquals("ACTIVE", listing(s).getStatus(), "the deal is off: back on sale");
        assertTrue(alertsFor(buyer, MarketOfferService.OFFER).stream().anyMatch(x -> x.getMessage().contains("called off the deal")));
        assertEquals(true, market.detail(buyer, id).get("canOffer"), "they can make a new one");
        Map<String, Object> o2 = offers.make(buyer, id, price(440_000));
        offers.buyerAct(buyer, (Long) o2.get("id"), "withdraw");
        assertEquals("WITHDRAWN", offer(o2).getStatus());
        assertThrows(ApiException.class, () -> offers.buyerAct(buyer, (Long) o2.get("id"), "agree"));
        // Taken off sale: open offers close and their buyers hear why.
        Map<String, Object> o3 = offers.make(buyer2, id, price(400_000));
        market.setStatus(owner, id, "WITHDRAWN");
        assertEquals("CLOSED", offer(o3).getStatus());
        assertTrue(alertsFor(buyer2, MarketOfferService.OFFER).stream().anyMatch(a -> a.getMessage().contains("took it off sale")));
    }
}
