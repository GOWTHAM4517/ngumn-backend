package com.ngumn.backend.service;

import com.ngumn.backend.dto.*;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.util.GeoUtil;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * Buy &amp; Sell: vehicles for sale and their test drives, for every kind of
 * seller - a vehicle's owner, someone selling it for family or a friend, a
 * used-vehicle dealer (an outside seller) and a showroom - and for buyers.
 *
 * <pre>
 *   Listing    the vehicle, price, papers, photos, where to see it.
 *              Its number plate is checked against cars reported stolen in
 *              Raksio: a stolen one can't be listed (its owner and the police
 *              are told where someone tried), and a listing whose car is
 *              reported stolen later stops showing. One plate, one listing.
 *   Trust      every seller and buyer shows how long they've been on Raksio,
 *              whether their mobile is saved, test drives done, sales, and the
 *              other side's ratings; a price guide compares similar vehicles.
 *   Booking    the buyer asks for a time (7 am - 8 pm, up to 14 days ahead);
 *              the seller says yes, no, or offers another time. Phone numbers
 *              and the full number plate are shared only once it's agreed.
 *              Reminders 30 minutes before; unanswered requests expire.
 *   Drive      when they meet, the seller types the 4-digit code on the
 *              buyer's phone - it's the person who booked. The car's GPS
 *              tracker (Car guard) or the phones in the car follow it: time
 *              allowed, area and speed limit; the seller is told when it's
 *              too fast, too far or late. Back and parked at the start, it
 *              ends by itself. Walk-in customers at a showroom or dealer too.
 *   After      both sides rate each other; "as described?" from the buyer.
 *   Sold       to a test-drive buyer, a Raksio account or someone outside;
 *              a Car guard car and its tracker can go to the buyer's account.
 *   Reports    fake / scam / stolen... - three people's reports hide it until
 *              an admin looks.
 *   Area       one city and the towns around it (MarketArea: Vijayawada,
 *              Gannavaram, Amaravati, Mangalagiri, Guntur...), so every buyer
 *              can reach every seller.
 *   Offers     a price, or the buyer's own vehicle in exchange
 *              (MarketOfferService); "Looking for" and "for sale near you" on
 *              the road (MarketRoadService).
 * </pre>
 *
 * Messages start with a fixed headline the app shows as the notification
 * title (src/lib/alert-text.ts).
 */
@Service
public class MarketService {

    public static final String MARKET = "Buy & Sell";
    public static final String DRIVE_REQUEST = "Test drive request";
    public static final String TEST_DRIVE = CarGuardService.TEST_DRIVE;

    static final Set<String> SELLER_TYPES = Set.of("OWNER", "INDIVIDUAL", "DEALER", "SHOWROOM");
    static final Set<String> VEHICLE_TYPES = Set.of("CAR", "BIKE", "SCOOTER", "AUTO", "COMMERCIAL");
    static final Set<String> FUELS = Set.of("PETROL", "DIESEL", "CNG", "ELECTRIC", "HYBRID", "LPG");
    static final Set<String> TRANSMISSIONS = Set.of("MANUAL", "AUTOMATIC");
    static final Set<String> REPORT_REASONS = Set.of("FAKE", "SCAM", "STOLEN", "WRONG_DETAILS", "ALREADY_SOLD", "OTHER");
    static final List<String> OPEN = List.of("ACTIVE", "RESERVED");
    static final List<String> UPCOMING = List.of("REQUESTED", "ACCEPTED");

    /** Open listings one seller may have. */
    static int maxOpen(String sellerType) {
        return switch (sellerType) {
            case "OWNER" -> 3;
            case "INDIVIDUAL" -> 5;
            default -> 200; // dealers and showrooms
        };
    }

    static final int MAX_PHOTOS = 6;
    static final int MAX_PHOTO_BYTES = 750_000;
    static final int REPORTS_TO_HIDE = 3;
    static final int OPEN_HOUR = 7;
    static final int CLOSE_HOUR = 20;
    static final Duration MIN_NOTICE = Duration.ofMinutes(30);
    static final Duration MAX_AHEAD = Duration.ofDays(14);
    static final Duration REMIND_BEFORE = Duration.ofMinutes(30);
    /** The code works from this long before the agreed time until START_AFTER after it. */
    static final Duration START_BEFORE = Duration.ofHours(2);
    static final Duration START_AFTER = Duration.ofHours(3);
    static final Duration CLOSE_AFTER_DUE = Duration.ofHours(6);
    static final double STILL_M = 40;
    static final Duration PARK_AFTER = Duration.ofMinutes(2);
    static final double BACK_M = 150;
    static final double MIN_DRIVE_M = 500;
    static final int PAGE = 30;

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MarketListingRepository listings;
    private final ListingPhotoRepository photos;
    private final TestDriveBookingRepository bookings;
    private final ListingReportRepository reports;
    private final GuardedCarRepository cars;
    private final CarPositionRepository positions;
    private final CarSightingRepository sightings;
    private final UserRepository users;
    private final AlertService alerts;
    private final IncidentService incidents;
    private final NgumnWebSocketHandler webSocket;
    private final MarketOfferRepository offers;
    private final MarketRoadService road;
    private final MarketArea area;

    public MarketService(MarketListingRepository listings, ListingPhotoRepository photos, TestDriveBookingRepository bookings,
                         ListingReportRepository reports, GuardedCarRepository cars, CarPositionRepository positions,
                         CarSightingRepository sightings, UserRepository users, AlertService alerts,
                         IncidentService incidents, NgumnWebSocketHandler webSocket, MarketOfferRepository offers,
                         MarketRoadService road, MarketArea area) {
        this.offers = offers;
        this.road = road;
        this.area = area;
        this.listings = listings;
        this.photos = photos;
        this.bookings = bookings;
        this.reports = reports;
        this.cars = cars;
        this.positions = positions;
        this.sightings = sightings;
        this.users = users;
        this.alerts = alerts;
        this.incidents = incidents;
        this.webSocket = webSocket;
    }

    // ================================================================== listings

    /**
     * Puts a vehicle up for sale. Not one transaction on purpose: when the
     * plate is a stolen car's, the sighting and the messages to its owner and
     * the police are kept although the listing itself is refused.
     */
    public Map<String, Object> create(User seller, ListingRequest r) {
        if (seller.getPhone() == null || seller.getPhone().isBlank()) {
            throw ApiException.badRequest("Add your mobile number in Profile first - buyers need a way to reach you.");
        }
        String sellerType = oneOf(r.getSellerType(), SELLER_TYPES, "Who's selling: the owner, someone selling for family or a friend, a dealer or a showroom?");
        String vehicleType = oneOf(r.getVehicleType(), VEHICLE_TYPES, "Pick the kind of vehicle: car, bike, scooter, auto or commercial.");
        String condition = r.getCondition() == null || r.getCondition().isBlank() ? "USED" : oneOf(r.getCondition(), Set.of("USED", "NEW"), "New or used?");
        if (listings.countBySellerIdAndStatusIn(seller.getId(), OPEN) >= maxOpen(sellerType)) {
            throw ApiException.badRequest(sellerType.equals("OWNER") || sellerType.equals("INDIVIDUAL")
                    ? "You can have " + maxOpen(sellerType) + " vehicles up for sale at a time. For more, sell as a dealer."
                    : "You have " + maxOpen(sellerType) + " vehicles up for sale - mark some sold first.");
        }
        if (text(r.getMake()) == null || text(r.getModel()) == null) throw ApiException.badRequest("Type the make and model, e.g. Maruti Suzuki Swift.");
        if (r.getPrice() == null || r.getPrice() < 1000) throw ApiException.badRequest("What's the price? Type it in rupees, e.g. 450000.");
        if (r.getCarId() != null && text(r.getPlate()) == null) {
            // Its plate is the one saved in Car guard.
            cars.findById(r.getCarId()).filter(c -> c.getOwner().getId().equals(seller.getId()) && c.getPlate() != null)
                    .ifPresent(c -> r.setPlate(c.getPlate()));
        }
        if (r.getLatitude() == null || r.getLongitude() == null || !validPoint(r.getLatitude(), r.getLongitude())) {
            throw ApiException.badRequest("Where can buyers see it? Turn on location.");
        }
        if (!area.contains(r.getLatitude(), r.getLongitude())) throw ApiException.badRequest(area.outsideMessage(r.getLatitude(), r.getLongitude()));
        if ((sellerType.equals("DEALER") || sellerType.equals("SHOWROOM")) && text(r.getBusinessName()) == null) {
            throw ApiException.badRequest("Type the " + (sellerType.equals("SHOWROOM") ? "showroom's" : "dealership's") + " name.");
        }
        if (condition.equals("USED")) {
            if (r.getYear() == null) throw ApiException.badRequest("Which year is it from?");
            if (text(r.getPlate()) == null) {
                throw ApiException.badRequest("Type its number plate. It's checked against stolen vehicles, and buyers only see part of it.");
            }
        }
        if (r.getYear() != null && r.getYear() > LocalDate.now().getYear() + 1) throw ApiException.badRequest("That year is in the future.");

        MarketListing l = MarketListing.builder()
                .seller(seller)
                .sellerType(sellerType)
                .vehicleType(vehicleType)
                .condition(condition)
                .make(r.getMake().trim())
                .model(r.getModel().trim())
                .price(r.getPrice())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .status("ACTIVE")
                .speedLimitKmh(vehicleType.equals("CAR") || vehicleType.equals("COMMERCIAL") ? 80 : 60)
                // Dealers and showrooms usually take the buyer's vehicle in exchange.
                .exchange(sellerType.equals("DEALER") || sellerType.equals("SHOWROOM"))
                .build();
        apply(l, r, seller);
        if (condition.equals("NEW") && l.getKmDriven() == null) l.setKmDriven(0);
        MarketListing saved = listings.save(l);
        changed(saved, null);
        // People looking for one like it hear about it.
        try {
            road.newListing(saved);
        } catch (RuntimeException e) {
            log.warn("Looking-for notes failed: {}", e.toString());
        }
        return detailFor(seller, saved);
    }

    @Transactional
    public Map<String, Object> update(User seller, Long id, ListingRequest r) {
        MarketListing l = mine(seller, id);
        if (!l.open()) throw ApiException.badRequest("It's " + l.getStatus().toLowerCase(Locale.ROOT) + " - it can't be changed now.");
        if (r.getSellerType() != null) l.setSellerType(oneOf(r.getSellerType(), SELLER_TYPES, "Who's selling?"));
        if (r.getVehicleType() != null) l.setVehicleType(oneOf(r.getVehicleType(), VEHICLE_TYPES, "Pick the kind of vehicle."));
        if (r.getCondition() != null) l.setCondition(oneOf(r.getCondition(), Set.of("USED", "NEW"), "New or used?"));
        if (r.getMake() != null) l.setMake(required(r.getMake(), "Type the make."));
        if (r.getModel() != null) l.setModel(required(r.getModel(), "Type the model."));
        if (r.getLatitude() != null || r.getLongitude() != null) {
            if (r.getLatitude() == null || r.getLongitude() == null || !validPoint(r.getLatitude(), r.getLongitude())) {
                throw ApiException.badRequest("That location doesn't look right.");
            }
            if (!area.contains(r.getLatitude(), r.getLongitude())) throw ApiException.badRequest(area.outsideMessage(r.getLatitude(), r.getLongitude()));
            l.setLatitude(r.getLatitude());
            l.setLongitude(r.getLongitude());
        }
        Long oldPrice = l.getPrice();
        if (r.getPrice() != null) {
            if (r.getPrice() < 1000) throw ApiException.badRequest("Type the price in rupees, e.g. 450000.");
            l.setPrice(r.getPrice());
        }
        apply(l, r, seller);
        if (l.getCondition().equals("USED") && l.getPlateKey() == null) throw ApiException.badRequest("A used vehicle needs its number plate.");
        if ((l.getSellerType().equals("DEALER") || l.getSellerType().equals("SHOWROOM")) && l.getBusinessName() == null) {
            throw ApiException.badRequest("Type the business name.");
        }
        MarketListing saved = listings.save(l);
        if (r.getPrice() != null && oldPrice != null && r.getPrice() < oldPrice) {
            // Price dropped: buyers with a test drive coming up hear about it.
            for (TestDriveBooking b : bookings.findByListingIdOrderByMeetAtDesc(l.getId())) {
                if (b.upcoming() && b.getBuyer() != null) {
                    tellAboutBooking(b.getBuyer(), RiskLevel.LOW, MARKET + ": the price of the " + l.title() + " is down to "
                            + rupees(l.getPrice()) + " (was " + rupees(oldPrice) + ").", b);
                }
            }
        }
        changed(saved, null);
        return detailFor(seller, saved);
    }

    /** Make, model, papers, plate, car... - whatever was sent (checks the plate against stolen cars and other listings). */
    private void apply(MarketListing l, ListingRequest r, User seller) {
        if (r.getBusinessName() != null) l.setBusinessName(text(r.getBusinessName()));
        if (l.getSellerType() != null && (l.getSellerType().equals("OWNER") || l.getSellerType().equals("INDIVIDUAL"))) l.setBusinessName(null);
        if (r.getVariant() != null) l.setVariant(text(r.getVariant()));
        if (r.getYear() != null) l.setYear(r.getYear());
        if (r.getFuel() != null) l.setFuel(r.getFuel().isBlank() ? null : oneOf(r.getFuel(), FUELS, "Fuel: petrol, diesel, CNG, electric, hybrid or LPG."));
        if (r.getTransmission() != null) {
            l.setTransmission(r.getTransmission().isBlank() ? null : oneOf(r.getTransmission(), TRANSMISSIONS, "Gears: manual or automatic."));
        }
        if (r.getKmDriven() != null) l.setKmDriven(r.getKmDriven());
        if (r.getOwnerCount() != null) l.setOwnerCount(r.getOwnerCount());
        if (r.getColour() != null) l.setColour(text(r.getColour()));
        if (r.getNegotiable() != null) l.setNegotiable(r.getNegotiable());
        if (r.getDescription() != null) l.setDescription(text(r.getDescription()));
        if (r.getRcInHand() != null) l.setRcInHand(r.getRcInHand());
        if (r.getInsuranceValid() != null) l.setInsuranceValid(r.getInsuranceValid());
        if (r.getServiceRecords() != null) l.setServiceRecords(r.getServiceRecords());
        if (r.getLoanCleared() != null) l.setLoanCleared(r.getLoanCleared());
        if (r.getArea() != null) l.setArea(text(r.getArea()));
        if (r.getTestDrives() != null) l.setTestDrives(r.getTestDrives());
        if (r.getExchange() != null) l.setExchange(r.getExchange());
        if (r.getShowOnRoad() != null) l.setShowOnRoad(r.getShowOnRoad());
        if (r.getDriveMinutes() != null) l.setDriveMinutes(r.getDriveMinutes());
        if (r.getDriveRadiusM() != null) l.setDriveRadiusM(r.getDriveRadiusM());
        if (r.getSpeedLimitKmh() != null) l.setSpeedLimitKmh(r.getSpeedLimitKmh());

        if (Boolean.TRUE.equals(r.getClearCar())) {
            l.setCarId(null);
        } else if (r.getCarId() != null && !r.getCarId().equals(l.getCarId())) {
            GuardedCar car = cars.findById(r.getCarId()).orElse(null);
            if (car == null || !car.getOwner().getId().equals(seller.getId())) throw ApiException.notFound("Car not found");
            if (car.stolen()) throw ApiException.badRequest("That car is reported stolen.");
            boolean listed = listings.findByCarIdAndStatusIn(car.getId(), OPEN).stream().anyMatch(o -> !o.getId().equals(l.getId()));
            if (listed) throw ApiException.badRequest(car.getName() + " is already up for sale.");
            l.setCarId(car.getId());
            if (text(r.getPlate()) == null && car.getPlate() != null && l.getPlateKey() == null) r.setPlate(car.getPlate());
            if (l.getColour() == null && car.getColour() != null) l.setColour(car.getColour());
        }

        if (r.getPlate() != null) {
            String key = CarGuardService.plateKey(r.getPlate());
            if (key != null && !key.equals(l.getPlateKey())) {
                if (key.length() < 6) throw ApiException.badRequest("That number plate looks too short.");
                checkNotStolen(seller, key, r.getPlate().trim(), l.getLatitude(), l.getLongitude(), l.getArea(), "sell");
                List<MarketListing> same = listings.findByPlateKeyAndStatusIn(key, OPEN).stream()
                        .filter(o -> !Objects.equals(o.getId(), l.getId())).toList();
                if (!same.isEmpty()) {
                    throw ApiException.badRequest(same.get(0).getSeller().getId().equals(seller.getId())
                            ? "You've already put " + r.getPlate().trim() + " up for sale."
                            : r.getPlate().trim() + " is already up for sale on Raksio by someone else. If it's yours, report that listing.");
                }
            }
            l.setPlate(key != null ? r.getPlate().trim().toUpperCase(Locale.ROOT) : null);
            l.setPlateKey(key);
        }
        if (l.getCarId() != null && l.getPlateKey() != null) {
            cars.findById(l.getCarId()).ifPresent(car -> {
                if (car.getPlateKey() != null && !car.getPlateKey().equals(l.getPlateKey())) {
                    throw ApiException.badRequest("That number plate isn't the one saved for " + car.getName() + " in Car guard.");
                }
            });
        }
    }

    /**
     * A plate reported stolen in Raksio can't be sold here or traded in
     * (doing: "sell" or "trade in"). Someone else trying is a sighting: its
     * owner and the police on the case are told who and where.
     */
    public void checkNotStolen(User who, String key, String typed, double lat, double lon, String place, String doing) {
        if (key == null) return;
        boolean sell = doing.equals("sell");
        for (GuardedCar car : cars.findByStatus("STOLEN")) {
            if (!key.equals(car.getPlateKey())) continue;
            if (car.getOwner().getId().equals(who.getId())) {
                throw ApiException.badRequest("Your " + car.getName() + " is reported stolen. Mark it found in Car guard first.");
            }
            String person = who.getName() + (who.getPhone() != null ? " (" + who.getPhone() + ")" : "");
            String where = place != null ? (sell ? " in " : " at ") + place : "";
            String tried = sell ? "sell it on Raksio Buy & Sell" : "trade it in on Raksio Buy & Sell";
            sightings.save(CarSighting.builder().carId(car.getId()).reporter(who).latitude(lat).longitude(lon)
                    .note("Someone tried to " + tried + where).source("MARKET").at(now()).build());
            alerts.raiseAboutCar(car.getOwner(), RiskLevel.HIGH, CarGuardService.STOLEN_SEEN + ": someone tried to "
                    + (sell ? "sell" : "trade in") + " your " + car.getName() + " on Raksio Buy & Sell" + where
                    + ". The police have been told - don't go there yourself.", lat, lon, car.getId(), car.getTheftIncidentId());
            if (car.getTheftIncidentId() != null) {
                boolean liveTracker = car.getLastSeenAt() != null && car.getLastSeenAt().isAfter(now().minusMinutes(10));
                if (!liveTracker) incidents.moveTo(car.getTheftIncidentId(), lat, lon);
                incidents.tellAssignee(car.getTheftIncidentId(), CarGuardService.STOLEN_SEEN + ": someone tried to "
                        + (sell ? "sell" : "trade in") + " the " + car.describe() + " on Raksio Buy & Sell" + where + ": " + person
                        + ". The request shows where.");
            }
            log.warn("Refused ({}): plate {} is reported stolen (car {})", doing, key, car.getId());
            throw ApiException.badRequest(typed + " is reported stolen in Raksio, so it can't be " + (sell ? "sold here." : "traded in."));
        }
    }

    /** ACTIVE (for sale), RESERVED (token paid - no new test drives) or WITHDRAWN (taken off). */
    @Transactional
    public Map<String, Object> setStatus(User seller, Long id, String status) {
        MarketListing l = mine(seller, id);
        String s = oneOf(status, Set.of("ACTIVE", "RESERVED", "WITHDRAWN"), "Status: ACTIVE, RESERVED or WITHDRAWN.");
        if (!l.open()) throw ApiException.badRequest("It's " + l.getStatus().toLowerCase(Locale.ROOT) + ".");
        if (s.equals("ACTIVE")) {
            offers.findByListingIdOrderByCreatedAtDesc(l.getId()).stream().filter(o -> "ACCEPTED".equals(o.getStatus())).findFirst().ifPresent(o -> {
                throw ApiException.badRequest("You accepted " + o.getBuyer().getName() + "'s offer - call that off first (Offers).");
            });
        }
        if (s.equals("WITHDRAWN")) {
            requireNoDrive(l);
            closeUpcoming(l, "the seller took it off sale");
            closeOffers(l, null, "the seller took it off sale");
        }
        l.setStatus(s);
        changed(listings.save(l), null);
        return detailFor(seller, l);
    }

    /** Sold: to one of its test-drive buyers, a Raksio account, or someone outside Raksio. */
    @Transactional
    public Map<String, Object> markSold(User seller, Long id, SoldRequest r) {
        MarketListing l = mine(seller, id);
        if (!l.open()) throw ApiException.badRequest("It's " + l.getStatus().toLowerCase(Locale.ROOT) + ".");
        requireNoDrive(l);
        User buyer = null;
        MarketOffer deal = null;
        if (r != null && r.getOfferId() != null) {
            deal = offers.findById(r.getOfferId()).orElse(null);
            if (deal == null || !deal.getListing().getId().equals(l.getId())) throw ApiException.notFound("Offer not found");
            if (!"ACCEPTED".equals(deal.getStatus())) throw ApiException.badRequest("Accept the offer first.");
            buyer = deal.getBuyer();
        } else if (r != null && r.getBookingId() != null) {
            TestDriveBooking b = bookings.findById(r.getBookingId()).orElse(null);
            if (b == null || b.getListing() == null || !b.getListing().getId().equals(l.getId())) throw ApiException.notFound("Test drive not found");
            buyer = b.getBuyer();
            if (buyer == null) throw ApiException.badRequest(b.getBuyerName() + " isn't on Raksio - mark it sold without a buyer.");
        } else if (r != null && text(r.getTo()) != null) {
            buyer = findAccount(r.getTo());
            if (buyer == null) throw ApiException.notFound("No Raksio account with that email or number.");
        }
        if (buyer != null && buyer.getId().equals(seller.getId())) throw ApiException.badRequest("That's your own account.");
        l.setStatus("SOLD");
        l.setSoldAt(now());
        l.setSoldTo(buyer);
        l.setSoldPrice(r != null && r.getPrice() != null ? r.getPrice()
                : deal != null && !deal.exchange() && deal.getSellerValue() != null ? deal.getSellerValue() : l.getPrice());
        listings.save(l);
        closeUpcoming(l, "it has been sold");
        if (deal != null) {
            deal.setStatus("DONE");
            offers.save(deal);
            // The buyer's own listing of the vehicle they traded in comes off sale.
            if (deal.exchange() && deal.getExListingId() != null) {
                listings.findById(deal.getExListingId()).filter(MarketListing::open).ifPresent(x -> {
                    for (TestDriveBooking b : bookings.findByListingIdOrderByMeetAtDesc(x.getId())) if (b.active()) finish(b, now());
                    closeUpcoming(x, "it was exchanged");
                    closeOffers(x, null, "it was exchanged");
                    x.setStatus("WITHDRAWN");
                    changed(listings.save(x), null);
                });
            }
        }
        closeOffers(l, deal, "it has been sold");

        boolean handed = false;
        if (buyer != null && l.getCarId() != null && r != null && Boolean.TRUE.equals(r.getHandOverTracker())) {
            handed = handOver(l.getCarId(), seller, buyer);
        }
        if (buyer != null) {
            alerts.raiseAboutMarket(buyer, RiskLevel.LOW, MARKET + ": " + seller.getName() + " marked the " + l.title() + " as sold to you"
                    + (handed ? ", with its GPS tracker - it's in your Car guard" : "") + ". Transfer the RC in your name at the RTO.",
                    null, null, l.getId(), null);
        }
        changed(l, null);
        Map<String, Object> m = detailFor(seller, l);
        m.put("handedOver", handed);
        m.put("soldTo", buyer != null ? buyer.getName() : null);
        m.put("exchangeOffer", deal != null && deal.exchange() ? MarketOfferService.view(deal, seller) : null);
        return m;
    }

    /** The Car guard car and its tracker go to the buyer's account, without the seller's trail and test drives. */
    private boolean handOver(Long carId, User seller, User buyer) {
        GuardedCar car = cars.findById(carId).orElse(null);
        if (car == null || !car.getOwner().getId().equals(seller.getId()) || car.stolen()) return false;
        if (car.getPlateKey() != null && cars.findByOwnerIdOrderByCreatedAtAsc(buyer.getId()).stream()
                .anyMatch(o -> car.getPlateKey().equals(o.getPlateKey()))) {
            return false;
        }
        positions.deleteByCarId(car.getId());
        CarGuardService.resetForNewOwner(car, buyer, newTrackToken());
        cars.save(car);
        alerts.raiseAboutCar(buyer, RiskLevel.LOW, CarGuardService.CAR_GUARD + ": " + seller.getName() + " has handed your "
                + car.describe() + " over to you, with its tracker. Open Car guard to see it.", null, null, car.getId(), null);
        webSocket.broadcast("CAR", Map.of("id", car.getId(), "ownerId", buyer.getId()));
        return true;
    }

    private String newTrackToken() {
        for (int i = 0; i < 10; i++) {
            String t = random(24);
            if (cars.findByTrackToken(t).isEmpty()) return t;
        }
        throw new IllegalStateException("Couldn't make a link");
    }

    private void requireNoDrive(MarketListing l) {
        boolean out = bookings.findByListingIdOrderByMeetAtDesc(l.getId()).stream().anyMatch(TestDriveBooking::active);
        if (out) throw ApiException.badRequest("A test drive is going on - end it first.");
    }

    /** Open and accepted offers (but the one it was sold on) are closed, and their buyers told why. */
    private void closeOffers(MarketListing l, MarketOffer except, String why) {
        for (MarketOffer o : offers.findByListingIdOrderByCreatedAtDesc(l.getId())) {
            if (except != null && o.getId().equals(except.getId())) continue;
            if (!o.open() && !"ACCEPTED".equals(o.getStatus())) continue;
            o.setStatus("CLOSED");
            offers.save(o);
            alerts.raiseAboutMarket(o.getBuyer(), RiskLevel.LOW, MarketOfferService.OFFER + ": your offer for the " + l.title()
                    + " is closed - " + why + ".", null, null, l.getId(), null);
        }
    }

    /** Asked-for and agreed test drives are called off, and their buyers told why. */
    private void closeUpcoming(MarketListing l, String why) {
        for (TestDriveBooking b : bookings.findByListingIdOrderByMeetAtDesc(l.getId())) {
            if (!b.upcoming()) continue;
            b.setStatus("CANCELLED");
            b.setCancelledBy("SELLER");
            b.setAnswer(why);
            bookings.save(b);
            if (b.getBuyer() != null) {
                tellAboutBooking(b.getBuyer(), RiskLevel.LOW, TEST_DRIVE + ": your test drive of the " + l.title() + " "
                        + when(b.getMeetAt()) + " is off - " + why + ".", b);
            }
        }
    }

    // ------------------------------------------------------------------ photos

    @Transactional
    public Map<String, Object> addPhoto(User seller, Long id, PhotoRequest r) {
        MarketListing l = mine(seller, id);
        if (!l.open()) throw ApiException.badRequest("It's " + l.getStatus().toLowerCase(Locale.ROOT) + ".");
        List<String> tokens = tokens(l);
        if (tokens.size() >= MAX_PHOTOS) throw ApiException.badRequest("Up to " + MAX_PHOTOS + " photos.");
        String b64 = r.getData().trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("That photo didn't come through - try again.");
        }
        if (bytes.length > MAX_PHOTO_BYTES) throw ApiException.badRequest("That photo is too big - the app shrinks photos; try again.");
        String type = imageType(bytes);
        if (type == null) throw ApiException.badRequest("Send a JPEG, PNG or WebP photo.");
        String token = random(24);
        photos.save(ListingPhoto.builder().listingId(l.getId()).token(token).contentType(type).data(bytes).build());
        tokens.add(token);
        l.setPhotoTokens(String.join(",", tokens));
        changed(listings.save(l), null);
        return detailFor(seller, l);
    }

    @Transactional
    public Map<String, Object> removePhoto(User seller, Long id, String token) {
        MarketListing l = mine(seller, id);
        List<String> tokens = tokens(l);
        if (!tokens.remove(token)) throw ApiException.notFound("Photo not found");
        photos.findByToken(token).ifPresent(photos::delete);
        l.setPhotoTokens(tokens.isEmpty() ? null : String.join(",", tokens));
        changed(listings.save(l), null);
        return detailFor(seller, l);
    }

    /** /api/market/photo/{token} - public (the random token is the key), so an image tag can load it. */
    public ListingPhoto photo(String token) {
        if (token == null || !token.matches("[a-z0-9]{10,32}")) throw ApiException.notFound("Photo not found");
        return photos.findByToken(token).orElseThrow(() -> ApiException.notFound("Photo not found"));
    }

    static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static List<String> tokens(MarketListing l) {
        List<String> out = new ArrayList<>();
        if (l.getPhotoTokens() == null) return out;
        for (String t : l.getPhotoTokens().split(",")) if (!t.isBlank()) out.add(t.trim());
        return out;
    }

    // ------------------------------------------------------------------ finding vehicles

    /** What a buyer can filter on. Missing = any. */
    public record Filters(Double lat, Double lng, Double radiusKm, String type, String q, Long minPrice, Long maxPrice,
                          String sellerType, String fuel, String transmission, Integer minYear, Integer maxKm,
                          String condition, String sort, Integer offset, Boolean exchange) {
    }

    public Map<String, Object> browse(User viewer, Filters f) {
        Set<String> stolenPlates = new HashSet<>();
        Set<Long> stolenCars = new HashSet<>();
        for (GuardedCar c : cars.findByStatus("STOLEN")) {
            stolenCars.add(c.getId());
            if (c.getPlateKey() != null) stolenPlates.add(c.getPlateKey());
        }
        // Distances only when the buyer is in the area; otherwise the whole area, newest first.
        boolean here = f.lat() != null && f.lng() != null && validPoint(f.lat(), f.lng()) && area.contains(f.lat(), f.lng());
        double radiusM = f.radiusKm() == null || f.radiusKm() <= 0 ? Double.MAX_VALUE : Math.min(f.radiusKm(), 1000) * 1000;
        String q = f.q() == null ? null : f.q().trim().toLowerCase(Locale.ROOT);
        String[] words = q == null || q.isEmpty() ? new String[0] : q.split("\\s+");

        Predicate<MarketListing> keep = l -> {
            if (l.getPlateKey() != null && stolenPlates.contains(l.getPlateKey())) return false;
            if (l.getCarId() != null && stolenCars.contains(l.getCarId())) return false;
            if (!area.contains(l.getLatitude(), l.getLongitude())) return false;
            if (Boolean.TRUE.equals(f.exchange()) && !Boolean.TRUE.equals(l.getExchange())) return false;
            if (f.type() != null && !f.type().isBlank() && !f.type().equalsIgnoreCase(l.getVehicleType())) return false;
            if (f.sellerType() != null && !f.sellerType().isBlank()) {
                // "Dealers" covers dealers and showrooms; "Owners" covers owners and people selling for family.
                String want = f.sellerType().toUpperCase(Locale.ROOT);
                boolean business = l.getSellerType().equals("DEALER") || l.getSellerType().equals("SHOWROOM");
                if (want.equals("BUSINESS") ? !business : want.equals("PRIVATE") ? business : !want.equals(l.getSellerType())) return false;
            }
            if (f.condition() != null && !f.condition().isBlank() && !f.condition().equalsIgnoreCase(l.getCondition())) return false;
            if (f.fuel() != null && !f.fuel().isBlank() && !f.fuel().equalsIgnoreCase(l.getFuel())) return false;
            if (f.transmission() != null && !f.transmission().isBlank() && !f.transmission().equalsIgnoreCase(l.getTransmission())) return false;
            if (f.minPrice() != null && l.getPrice() < f.minPrice()) return false;
            if (f.maxPrice() != null && l.getPrice() > f.maxPrice()) return false;
            if (f.minYear() != null && (l.getYear() == null || l.getYear() < f.minYear())) return false;
            if (f.maxKm() != null && l.getKmDriven() != null && l.getKmDriven() > f.maxKm()) return false;
            if (here && GeoUtil.distanceMeters(f.lat(), f.lng(), l.getLatitude(), l.getLongitude()) > radiusM) return false;
            if (words.length > 0) {
                String hay = (l.title() + " " + nz(l.getColour()) + " " + nz(l.getBusinessName()) + " " + nz(l.getArea()) + " "
                        + nz(l.getFuel()) + " " + l.getVehicleType()).toLowerCase(Locale.ROOT);
                for (String w : words) if (!hay.contains(w)) return false;
            }
            return true;
        };
        List<MarketListing> found = new ArrayList<>(listings.findByStatusIn(OPEN).stream().filter(keep).toList());
        String sort = f.sort() == null ? (here ? "NEAREST" : "NEWEST") : f.sort().toUpperCase(Locale.ROOT);
        Comparator<MarketListing> order = switch (sort) {
            case "PRICE_LOW" -> Comparator.comparing(MarketListing::getPrice);
            case "PRICE_HIGH" -> Comparator.comparing(MarketListing::getPrice).reversed();
            case "NEAREST" -> here
                    ? Comparator.comparingDouble(l -> GeoUtil.distanceMeters(f.lat(), f.lng(), l.getLatitude(), l.getLongitude()))
                    : Comparator.comparing(MarketListing::getCreatedAt).reversed();
            case "KM_LOW" -> Comparator.comparing(l -> l.getKmDriven() != null ? l.getKmDriven() : Integer.MAX_VALUE);
            case "YEAR_NEW" -> Comparator.comparing((MarketListing l) -> l.getYear() != null ? l.getYear() : 0).reversed();
            default -> Comparator.comparing(MarketListing::getCreatedAt).reversed();
        };
        // Reserved ones go to the end: still visible, but someone's ahead.
        found.sort(Comparator.comparing((MarketListing l) -> "RESERVED".equals(l.getStatus())).thenComparing(order));
        int from = Math.max(0, f.offset() != null ? f.offset() : 0);
        Map<Long, Map<String, Object>> trustCache = new HashMap<>();
        List<Map<String, Object>> page = found.stream().skip(from).limit(PAGE)
                .map(l -> card(l, viewer, here ? f.lat() : null, here ? f.lng() : null, trustCache)).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", found.size());
        m.put("offset", from);
        m.put("listings", page);
        m.put("inArea", here || f.lat() == null);
        m.put("area", area.view());
        return m;
    }

    /** One vehicle: everything a buyer needs - or, for its seller, its test-drive requests too. */
    @Transactional
    public Map<String, Object> detail(User viewer, Long id) {
        MarketListing l = listings.findById(id).orElseThrow(() -> ApiException.notFound("This vehicle isn't for sale any more."));
        boolean mine = l.getSeller().getId().equals(viewer.getId());
        boolean admin = viewer.getRole() == Role.ADMIN;
        // After it's sold or taken off: still open to buyers who had a test drive agreed (and the one who bought it).
        boolean booked = !mine && bookings.findByListingIdOrderByMeetAtDesc(l.getId()).stream()
                .anyMatch(b -> b.getBuyer() != null && b.getBuyer().getId().equals(viewer.getId())
                        && List.of("ACCEPTED", "ACTIVE", "ENDED").contains(b.getStatus()));
        boolean boughtIt = l.getSoldTo() != null && l.getSoldTo().getId().equals(viewer.getId());
        if (!l.open() && !mine && !admin && !booked && !boughtIt) throw ApiException.notFound("This vehicle isn't for sale any more.");
        if (!mine) {
            l.setViews(l.getViews() + 1);
            listings.save(l);
        }
        return detailFor(viewer, l);
    }

    Map<String, Object> detailFor(User viewer, MarketListing l) {
        boolean mine = l.getSeller().getId().equals(viewer.getId());
        Map<String, Object> m = card(l, viewer, null, null, new HashMap<>());
        List<TestDriveBooking> all = bookings.findByListingIdOrderByMeetAtDesc(l.getId());
        TestDriveBooking myBooking = mine ? null : all.stream()
                .filter(b -> b.getBuyer() != null && b.getBuyer().getId().equals(viewer.getId())).findFirst().orElse(null);
        boolean agreed = myBooking != null && List.of("ACCEPTED", "ACTIVE", "ENDED").contains(myBooking.getStatus());
        m.put("mine", mine);
        m.put("variant", l.getVariant());
        m.put("colour", l.getColour());
        m.put("description", l.getDescription());
        m.put("plate", mine || agreed ? l.getPlate() : maskPlate(l.getPlateKey()));
        m.put("plateHidden", !(mine || agreed) && l.getPlateKey() != null);
        m.put("photos", tokens(l).stream().map(MarketService::photoPath).toList());
        m.put("photoTokens", mine ? tokens(l) : List.of());
        // A private seller's spot is often their home: about 1 km until a test drive is agreed. Dealers and showrooms are shops.
        boolean exact = mine || agreed || l.getSellerType().equals("DEALER") || l.getSellerType().equals("SHOWROOM");
        m.put("latitude", exact ? l.getLatitude() : Math.round(l.getLatitude() * 100) / 100.0);
        m.put("longitude", exact ? l.getLongitude() : Math.round(l.getLongitude() * 100) / 100.0);
        m.put("locationExact", exact);
        m.put("driveMinutes", l.getDriveMinutes());
        m.put("driveRadiusM", l.getDriveRadiusM());
        m.put("speedLimitKmh", l.getSpeedLimitKmh());
        m.put("views", l.getViews());
        m.put("updatedAt", l.getUpdatedAt());
        m.put("soldAt", l.getSoldAt());
        m.put("priceGuide", priceGuide(l));
        m.put("sellerPhone", agreed ? l.getSeller().getPhone() : null);
        m.put("carId", mine ? l.getCarId() : null);
        boolean stolen = l.getPlateKey() != null && cars.findByStatus("STOLEN").stream().anyMatch(c -> l.getPlateKey().equals(c.getPlateKey()));
        m.put("stolen", stolen);
        m.put("reportedByMe", !mine && reports.existsByListingIdAndReporterId(l.getId(), viewer.getId()));
        m.put("myBooking", myBooking != null ? bookingView(myBooking, viewer) : null);
        m.put("showOnRoad", mine ? l.getShowOnRoad() : null);
        if (mine && l.getSoldTo() != null) m.put("soldTo", l.getSoldTo().getName());
        List<MarketOffer> os = offers.findByListingIdOrderByCreatedAtDesc(l.getId());
        if (mine) {
            m.put("bookings", all.stream().limit(50).map(b -> bookingView(b, viewer)).toList());
            m.put("reportCount", l.getReportCount());
            m.put("offers", os.stream().limit(50).map(o -> MarketOfferService.view(o, viewer)).toList());
            m.put("reach", road.reach(l.getId()));
        } else {
            MarketOffer myOffer = os.stream().filter(o -> o.getBuyer().getId().equals(viewer.getId())).findFirst().orElse(null);
            m.put("myOffer", myOffer != null ? MarketOfferService.view(myOffer, viewer) : null);
            boolean dealForMe = myOffer != null && "ACCEPTED".equals(myOffer.getStatus());
            // Reserved for this buyer (their offer was accepted): they can still come for a test drive.
            m.put("canBook", Boolean.TRUE.equals(l.getTestDrives()) && ("ACTIVE".equals(l.getStatus()) || ("RESERVED".equals(l.getStatus()) && dealForMe)));
            m.put("canOffer", "ACTIVE".equals(l.getStatus()) && (Boolean.TRUE.equals(l.getNegotiable()) || Boolean.TRUE.equals(l.getExchange()))
                    && (myOffer == null || !(myOffer.open() || dealForMe)));
        }
        return m;
    }

    /** A vehicle in a list. */
    Map<String, Object> card(MarketListing l, User viewer, Double lat, Double lng, Map<Long, Map<String, Object>> trustCache) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("title", l.title());
        m.put("make", l.getMake());
        m.put("model", l.getModel());
        m.put("year", l.getYear());
        m.put("price", l.getPrice());
        m.put("negotiable", l.getNegotiable());
        m.put("vehicleType", l.getVehicleType());
        m.put("condition", l.getCondition());
        m.put("fuel", l.getFuel());
        m.put("transmission", l.getTransmission());
        m.put("kmDriven", l.getKmDriven());
        m.put("ownerCount", l.getOwnerCount());
        m.put("sellerType", l.getSellerType());
        m.put("businessName", l.getBusinessName());
        m.put("area", l.getArea());
        m.put("status", l.getStatus());
        m.put("testDrives", l.getTestDrives());
        m.put("exchange", l.getExchange());
        m.put("papers", Map.of("rcInHand", l.getRcInHand(), "insuranceValid", l.getInsuranceValid(),
                "serviceRecords", l.getServiceRecords(), "loanCleared", l.getLoanCleared()));
        m.put("plateChecked", l.getPlateKey() != null);
        m.put("tracked", l.getCarId() != null);
        List<String> t = tokens(l);
        m.put("photo", t.isEmpty() ? null : photoPath(t.get(0)));
        m.put("photoCount", t.size());
        m.put("createdAt", l.getCreatedAt());
        m.put("distanceKm", lat != null ? Math.round(GeoUtil.distanceMeters(lat, lng, l.getLatitude(), l.getLongitude()) / 100.0) / 10.0 : null);
        m.put("seller", trustCache.computeIfAbsent(l.getSeller().getId(), k -> sellerTrust(l.getSeller())));
        return m;
    }

    /**
     * Similar vehicles (same kind, make and model, within two years) for sale
     * now or sold in the last year: the range, and where this price sits.
     */
    Map<String, Object> priceGuide(MarketListing l) {
        LocalDateTime yearAgo = now().minusDays(365);
        List<Long> prices = new ArrayList<>();
        for (MarketListing o : listings.findByStatusIn(List.of("ACTIVE", "RESERVED", "SOLD"))) {
            if (o.getId().equals(l.getId()) || !o.getVehicleType().equals(l.getVehicleType())) continue;
            if (!o.getMake().equalsIgnoreCase(l.getMake()) || !o.getModel().equalsIgnoreCase(l.getModel())) continue;
            if (l.getYear() != null && o.getYear() != null && Math.abs(o.getYear() - l.getYear()) > 2) continue;
            if ("SOLD".equals(o.getStatus()) && (o.getSoldAt() == null || o.getSoldAt().isBefore(yearAgo))) continue;
            prices.add("SOLD".equals(o.getStatus()) && o.getSoldPrice() != null ? o.getSoldPrice() : o.getPrice());
        }
        if (prices.size() < 2) return null;
        Collections.sort(prices);
        long median = prices.size() % 2 == 1 ? prices.get(prices.size() / 2)
                : (prices.get(prices.size() / 2 - 1) + prices.get(prices.size() / 2)) / 2;
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("count", prices.size());
        g.put("low", prices.get(0));
        g.put("typical", median);
        g.put("high", prices.get(prices.size() - 1));
        g.put("verdict", l.getPrice() < median * 0.9 ? "LOW" : l.getPrice() > median * 1.1 ? "HIGH" : "FAIR");
        return g;
    }

    /** My vehicles for sale, my test drives as a buyer, and requests for my vehicles. */
    public Map<String, Object> mine(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> cache = new HashMap<>();
        List<Map<String, Object>> mineList = new ArrayList<>();
        for (MarketListing l : listings.findBySellerIdOrderByCreatedAtDesc(user.getId())) {
            if ("WITHDRAWN".equals(l.getStatus()) && l.getUpdatedAt() != null && l.getUpdatedAt().isBefore(now().minusDays(30))) continue;
            Map<String, Object> c = card(l, user, null, null, cache);
            List<TestDriveBooking> bs = bookings.findByListingIdOrderByMeetAtDesc(l.getId());
            c.put("waitingForYou", bs.stream().filter(b -> waitingFor(b).equals("SELLER")).count());
            c.put("upcoming", bs.stream().filter(b -> "ACCEPTED".equals(b.getStatus())).count());
            c.put("views", l.getViews());
            List<MarketOffer> os = offers.findByListingIdOrderByCreatedAtDesc(l.getId());
            c.put("offersWaiting", os.stream().filter(o -> "OPEN".equals(o.getStatus())).count());
            c.put("offerAccepted", os.stream().anyMatch(o -> "ACCEPTED".equals(o.getStatus())));
            if (l.open()) c.put("reach", road.reach(l.getId()));
            mineList.add(c);
        }
        m.put("listings", mineList);
        // Offers: the ones I made, and the ones on my vehicles - waiting for me first, then the newest.
        List<Map<String, Object>> ofs = new ArrayList<>();
        offers.findByBuyerIdOrderByCreatedAtDesc(user.getId()).stream().limit(50).forEach(o -> ofs.add(MarketOfferService.view(o, user)));
        for (MarketListing l : listings.findBySellerIdOrderByCreatedAtDesc(user.getId())) {
            offers.findByListingIdOrderByCreatedAtDesc(l.getId()).stream().limit(20).forEach(o -> ofs.add(MarketOfferService.view(o, user)));
        }
        ofs.sort(Comparator.<Map<String, Object>, Boolean>comparing(o -> !waitingOn(o)).thenComparing(
                o -> (LocalDateTime) (o.get("answeredAt") != null ? o.get("answeredAt") : o.get("createdAt")), Comparator.nullsLast(Comparator.reverseOrder())));
        m.put("offers", ofs.size() > 80 ? ofs.subList(0, 80) : ofs);
        m.put("asBuyer", bookings.findByBuyerIdOrderByMeetAtDesc(user.getId()).stream().limit(50).map(b -> bookingView(b, user)).toList());
        m.put("asSeller", bookings.findBySellerIdOrderByMeetAtDesc(user.getId()).stream().limit(80).map(b -> bookingView(b, user)).toList());
        return m;
    }

    /** An offer the viewer has to answer. */
    private static boolean waitingOn(Map<String, Object> o) {
        return o.get("waitingFor") != null && o.get("waitingFor").equals(o.get("role"));
    }

    /** "Is this number stolen?" - for anyone about to buy, here or anywhere else. */
    public Map<String, Object> checkPlate(User viewer, String plate) {
        String key = CarGuardService.plateKey(plate);
        if (key == null || key.length() < 6) throw ApiException.badRequest("Type the full number plate, e.g. AP 16 BX 2231.");
        boolean stolen = cars.findByStatus("STOLEN").stream().anyMatch(c -> key.equals(c.getPlateKey()));
        long listed = listings.findByPlateKeyAndStatusIn(key, OPEN).size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plate", plate.trim().toUpperCase(Locale.ROOT));
        m.put("stolen", stolen);
        m.put("listed", listed);
        m.put("message", stolen
                ? "Reported stolen in Raksio. Don't buy it - call 112 if you know where it is."
                : "Not reported stolen in Raksio. Also check the RC, the chassis number and that there's no loan on it (Parivahan / mParivahan).");
        return m;
    }

    // ------------------------------------------------------------------ reports and the admin

    @Transactional
    public Map<String, Object> report(User reporter, Long id, ListingReportRequest r) {
        MarketListing l = listings.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
        if (l.getSeller().getId().equals(reporter.getId())) throw ApiException.badRequest("That's your own listing.");
        String reason = oneOf(r.getReason(), REPORT_REASONS, "Why: fake, scam, stolen, wrong details, already sold or other?");
        Map<String, Object> m = new LinkedHashMap<>();
        if (reports.existsByListingIdAndReporterId(l.getId(), reporter.getId())) {
            m.put("already", true);
            m.put("message", "You've reported it already - thank you.");
            return m;
        }
        reports.save(ListingReport.builder().listingId(l.getId()).reporter(reporter).reason(reason).note(text(r.getNote())).build());
        l.setReportCount(l.getReportCount() + 1);
        boolean hidden = false;
        if (l.getReportCount() >= REPORTS_TO_HIDE && l.open()) {
            l.setStatus("HIDDEN");
            hidden = true;
            alerts.raiseAboutMarket(l.getSeller(), RiskLevel.MEDIUM, MARKET + ": your " + l.title() + " is hidden for now - several people "
                    + "reported it. The Raksio admin will look at it.", null, null, l.getId(), null);
        }
        listings.save(l);
        changed(l, null);
        m.put("hidden", hidden);
        m.put("message", "Thank you - " + (hidden ? "it's hidden until the admin checks it." : "the admin will look at it."));
        return m;
    }

    /** Admin: vehicles hidden or reported, with the reasons. */
    public List<Map<String, Object>> reportedForAdmin(User admin) {
        AdminControlService.requireAdmin(admin);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MarketListing l : listings.findByStatusIn(List.of("ACTIVE", "RESERVED", "HIDDEN"))) {
            if (l.getReportCount() <= 0) continue;
            Map<String, Object> m = card(l, admin, null, null, new HashMap<>());
            m.put("sellerName", l.getSeller().getName());
            m.put("sellerPhone", l.getSeller().getPhone());
            m.put("plate", l.getPlate());
            m.put("reportCount", l.getReportCount());
            m.put("reports", reports.findByListingIdOrderByCreatedAtDesc(l.getId()).stream().map(x -> {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("reason", x.getReason());
                y.put("note", x.getNote());
                y.put("by", x.getReporter().getName());
                y.put("at", x.getCreatedAt());
                return y;
            }).toList());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> x) -> !"HIDDEN".equals(x.get("status")))
                .thenComparing(x -> -((Number) x.get("reportCount")).intValue()));
        return out;
    }

    /** Admin: RESTORE (back for sale) or REMOVE (taken off) a reported vehicle. */
    @Transactional
    public Map<String, Object> adminAction(User admin, Long id, String action) {
        AdminControlService.requireAdmin(admin);
        MarketListing l = listings.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (a.equals("RESTORE")) {
            l.setStatus("ACTIVE");
            l.setReportCount(0);
            alerts.raiseAboutMarket(l.getSeller(), RiskLevel.LOW, MARKET + ": your " + l.title() + " is back up for sale.", null, null, l.getId(), null);
        } else if (a.equals("REMOVE")) {
            requireNoDrive(l);
            closeUpcoming(l, "it was taken off Raksio");
            l.setStatus("WITHDRAWN");
            alerts.raiseAboutMarket(l.getSeller(), RiskLevel.MEDIUM, MARKET + ": the Raksio admin took your " + l.title()
                    + " off sale after people reported it.", null, null, l.getId(), null);
        } else {
            throw ApiException.badRequest("RESTORE or REMOVE.");
        }
        changed(listings.save(l), null);
        return card(l, admin, null, null, new HashMap<>());
    }

    // ================================================================== test drives

    /** The buyer asks for a test drive at a time. */
    @Transactional
    public Map<String, Object> book(User buyer, Long listingId, BookingRequest r) {
        MarketListing l = listings.findById(listingId).orElseThrow(() -> ApiException.notFound("This vehicle isn't for sale any more."));
        if (l.getSeller().getId().equals(buyer.getId())) throw ApiException.badRequest("That's your own vehicle.");
        // Reserved: only for the buyer whose offer was accepted (they come to see and drive it before paying).
        boolean dealForMe = "RESERVED".equals(l.getStatus()) && offers.findByListingIdOrderByCreatedAtDesc(l.getId()).stream()
                .anyMatch(o -> o.getBuyer().getId().equals(buyer.getId()) && "ACCEPTED".equals(o.getStatus()));
        if (!"ACTIVE".equals(l.getStatus()) && !dealForMe) {
            throw ApiException.badRequest("RESERVED".equals(l.getStatus()) ? "It's reserved for another buyer." : "It isn't for sale any more.");
        }
        if (!Boolean.TRUE.equals(l.getTestDrives())) throw ApiException.badRequest("The seller isn't taking test drives for this one - call or message instead.");
        if (buyer.getPhone() == null || buyer.getPhone().isBlank()) {
            throw ApiException.badRequest("Add your mobile number in Profile first, so the seller can reach you.");
        }
        boolean open = bookings.findByListingIdOrderByMeetAtDesc(l.getId()).stream()
                .anyMatch(b -> b.getBuyer() != null && b.getBuyer().getId().equals(buyer.getId()) && (b.upcoming() || b.active()));
        if (open) throw ApiException.badRequest("You already have a test drive of this one - change or cancel that.");
        LocalDateTime at = checkTime(r.getMeetAt());
        TestDriveBooking b = bookings.save(TestDriveBooking.builder()
                .listing(l)
                .carId(l.getCarId())
                .seller(l.getSeller())
                .buyer(buyer)
                .buyerName(buyer.getName())
                .buyerPhone(buyer.getPhone())
                .driverLicence(Boolean.TRUE.equals(r.getDriverLicence()))
                .note(text(r.getNote()))
                .meetAt(at)
                .status("REQUESTED")
                .build());
        tellAboutBooking(l.getSeller(), RiskLevel.MEDIUM, DRIVE_REQUEST + ": " + buyer.getName() + " wants to test-drive your "
                + l.title() + " " + when(at) + (Boolean.TRUE.equals(b.getDriverLicence()) ? "" : " (riding along - no licence for it)")
                + ". Say yes, no or offer another time.", b);
        changed(l, b);
        return bookingView(b, buyer);
    }

    @Transactional
    public Map<String, Object> accept(User seller, Long id) {
        TestDriveBooking b = asSeller(seller, id);
        if (!"REQUESTED".equals(b.getStatus())) throw ApiException.badRequest("It's " + b.getStatus().toLowerCase(Locale.ROOT) + ".");
        if (b.getMeetAt().isBefore(now())) throw ApiException.badRequest("That time has passed - offer another time.");
        if (b.getListing() != null && !b.getListing().open()) throw ApiException.badRequest("It isn't for sale any more.");
        clash(b, b.getMeetAt());
        b.setProposedAt(null);
        b.setStatus("ACCEPTED");
        b.setMeetCode(code());
        bookings.save(b);
        if (b.getBuyer() != null) {
            tellAboutBooking(b.getBuyer(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + seller.getName() + " said yes - " + when(b.getMeetAt())
                    + placeOf(b) + ". Open it for their number and your meeting code.", b);
        }
        changed(b.getListing(), b);
        return bookingView(b, seller);
    }

    @Transactional
    public Map<String, Object> decline(User seller, Long id, BookingActionRequest r) {
        TestDriveBooking b = asSeller(seller, id);
        if (!"REQUESTED".equals(b.getStatus())) throw ApiException.badRequest("It's " + b.getStatus().toLowerCase(Locale.ROOT) + ".");
        b.setStatus("DECLINED");
        b.setAnswer(r != null ? text(r.getReason()) : null);
        bookings.save(b);
        if (b.getBuyer() != null) {
            tellAboutBooking(b.getBuyer(), RiskLevel.LOW, TEST_DRIVE + ": " + seller.getName() + " can't do a test drive of the "
                    + titleOf(b) + " " + when(b.getMeetAt()) + (b.getAnswer() != null ? " (\"" + b.getAnswer() + "\")" : "") + ".", b);
        }
        changed(b.getListing(), b);
        return bookingView(b, seller);
    }

    /** The seller offers another time - the buyer agrees (agree) or cancels. */
    @Transactional
    public Map<String, Object> propose(User seller, Long id, BookingActionRequest r) {
        TestDriveBooking b = asSeller(seller, id);
        if (!b.upcoming()) throw ApiException.badRequest("It's " + b.getStatus().toLowerCase(Locale.ROOT) + ".");
        LocalDateTime at = checkTime(r != null ? r.getAt() : null);
        b.setProposedAt(at);
        b.setStatus("REQUESTED");
        b.setMeetCode(null);
        b.setReminded(false);
        b.setAnswer(r != null ? text(r.getReason()) : null);
        bookings.save(b);
        if (b.getBuyer() != null) {
            tellAboutBooking(b.getBuyer(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + seller.getName() + " asks if " + when(at)
                    + " works for the " + titleOf(b) + " instead. Open it to say yes.", b);
        }
        changed(b.getListing(), b);
        return bookingView(b, seller);
    }

    /** The buyer agrees to the seller's other time. */
    @Transactional
    public Map<String, Object> agree(User buyer, Long id) {
        TestDriveBooking b = asBuyer(buyer, id);
        if (!"REQUESTED".equals(b.getStatus()) || b.getProposedAt() == null) throw ApiException.badRequest("There's no new time to agree to.");
        if (b.getProposedAt().isBefore(now())) throw ApiException.badRequest("That time has passed - ask for another.");
        clash(b, b.getProposedAt());
        b.setMeetAt(b.getProposedAt());
        b.setProposedAt(null);
        b.setStatus("ACCEPTED");
        b.setMeetCode(code());
        bookings.save(b);
        tellAboutBooking(b.getSeller(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + buyer.getName() + " is coming " + when(b.getMeetAt())
                + " for the " + titleOf(b) + ".", b);
        changed(b.getListing(), b);
        return bookingView(b, buyer);
    }

    /** Either side calls it off before it starts. */
    @Transactional
    public Map<String, Object> cancel(User user, Long id, BookingActionRequest r) {
        TestDriveBooking b = party(user, id);
        if (!b.upcoming()) throw ApiException.badRequest("It's " + b.getStatus().toLowerCase(Locale.ROOT) + ".");
        boolean bySeller = b.getSeller().getId().equals(user.getId());
        b.setStatus("CANCELLED");
        b.setCancelledBy(bySeller ? "SELLER" : "BUYER");
        b.setAnswer(r != null ? text(r.getReason()) : null);
        bookings.save(b);
        User other = bySeller ? b.getBuyer() : b.getSeller();
        if (other != null) {
            tellAboutBooking(other, RiskLevel.LOW, TEST_DRIVE + ": " + user.getName() + " called off the test drive of the " + titleOf(b)
                    + " " + when(b.getMeetAt()) + (b.getAnswer() != null ? " (\"" + b.getAnswer() + "\")" : "") + ".", b);
        }
        changed(b.getListing(), b);
        return bookingView(b, user);
    }

    /** They've met: the seller types the code on the buyer's phone, and the drive starts. */
    @Transactional
    public Map<String, Object> start(User seller, Long id, BookingActionRequest r) {
        TestDriveBooking b = asSeller(seller, id);
        if (!"ACCEPTED".equals(b.getStatus())) {
            throw ApiException.badRequest(b.active() ? "It has started already." : "It's " + b.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        String typed = r != null && r.getCode() != null ? r.getCode().trim() : "";
        if (!typed.equals(b.getMeetCode())) throw ApiException.badRequest("That code doesn't match - ask the buyer to show the code in their Raksio.");
        LocalDateTime now = now();
        if (now.isBefore(b.getMeetAt().minus(START_BEFORE)) || now.isAfter(b.getMeetAt().plus(START_AFTER))) {
            throw ApiException.badRequest("This test drive is for " + when(b.getMeetAt()) + ".");
        }
        if (b.getListing() != null && !b.getListing().open()) throw ApiException.badRequest("It isn't for sale any more.");
        Double lat = r != null ? r.getLatitude() : null;
        Double lon = r != null ? r.getLongitude() : null;
        begin(b, lat, lon, null, null);
        if (b.getBuyer() != null) {
            tellAboutBooking(b.getBuyer(), RiskLevel.LOW, TEST_DRIVE + ": started - back by " + clock(b.getDueBackAt()) + ". Keep under "
                    + b.getSpeedLimitKmh() + " km/h and within " + km(b.getRadiusM()) + ". Drive safe!", b);
        }
        changed(b.getListing(), b);
        return bookingView(b, seller);
    }

    /** A walk-in customer at a showroom or dealer (no Raksio account): the drive starts now. */
    @Transactional
    public Map<String, Object> walkIn(User seller, Long listingId, BookingRequest r) {
        MarketListing l = mine(seller, listingId);
        if (!l.open()) throw ApiException.badRequest("It isn't for sale any more.");
        TestDriveBooking b = walkInBooking(seller, l, l.getCarId(), r);
        return bookingView(b, seller);
    }

    /** Car guard's "Start a test drive" on a car: on its listing if it has one, otherwise on the car alone. */
    @Transactional
    public Map<String, Object> walkInForCar(User owner, GuardedCar car, TestDriveRequest r) {
        if (car.stolen()) throw ApiException.badRequest("This car is reported stolen.");
        MarketListing l = listings.findByCarIdAndStatusIn(car.getId(), OPEN).stream().findFirst().orElse(null);
        BookingRequest br = new BookingRequest();
        br.setName(r.getCustomerName());
        br.setPhone(r.getCustomerPhone());
        br.setMinutes(r.getMinutes());
        br.setRadiusM(r.getRadiusM());
        if (l == null) {
            Double cLat = car.getHomeLatitude() != null ? car.getHomeLatitude() : car.getLastLatitude();
            if (cLat == null) throw ApiException.badRequest("Set the car's parking place (the showroom) first, so the drive knows where it starts.");
        }
        return driveView(walkInBooking(owner, l, car.getId(), br, car));
    }

    private TestDriveBooking walkInBooking(User seller, MarketListing l, Long carId, BookingRequest r) {
        GuardedCar car = carId != null ? cars.findById(carId).orElse(null) : null;
        return walkInBooking(seller, l, carId, r, car);
    }

    private TestDriveBooking walkInBooking(User seller, MarketListing l, Long carId, BookingRequest r, GuardedCar car) {
        String name = text(r.getName());
        if (name == null) throw ApiException.badRequest("Who's driving? Type the customer's name.");
        String phone = text(r.getPhone()) != null ? ContactService.normalise(r.getPhone()) : null;
        LocalDateTime now = now();
        TestDriveBooking b = TestDriveBooking.builder()
                .listing(l)
                .carId(carId)
                .seller(seller)
                .buyerName(name)
                .buyerPhone(phone)
                .walkIn(true)
                .driverLicence(r.getDriverLicence() == null || r.getDriverLicence())
                .meetAt(now)
                .status("ACCEPTED")
                .build();
        if (car != null && l == null) {
            // A car that isn't listed: Car guard's own settings.
            b.setSpeedLimitKmh(car.getSpeedLimitKmh() != null ? car.getSpeedLimitKmh() : 80);
        }
        Double lat = r.getLatitude();
        Double lon = r.getLongitude();
        if ((lat == null || lon == null) && car != null && l == null && car.getHomeLatitude() != null) {
            lat = car.getHomeLatitude();
            lon = car.getHomeLongitude();
        }
        begin(b, lat, lon, r.getMinutes(), r.getRadiusM());
        changed(l, b);
        return b;
    }

    /** Settings from the listing (or what was sent), the tracker or the phones, where it starts. */
    private void begin(TestDriveBooking b, Double lat, Double lon, Integer minutes, Integer radiusM) {
        MarketListing l = b.getListing();
        Long carId = b.getCarId();
        for (TestDriveBooking o : carId != null ? bookings.findByCarIdAndStatus(carId, "ACTIVE") : List.<TestDriveBooking>of()) {
            if (!Objects.equals(o.getId(), b.getId())) throw ApiException.badRequest("It's already out with " + o.getBuyerName() + ". End that test drive first.");
        }
        if (l != null) {
            for (TestDriveBooking o : bookings.findByListingIdOrderByMeetAtDesc(l.getId())) {
                if (o.active() && !Objects.equals(o.getId(), b.getId())) {
                    throw ApiException.badRequest("It's already out with " + o.getBuyerName() + ". End that test drive first.");
                }
            }
        }
        GuardedCar car = carId != null ? cars.findById(carId).orElse(null) : null;
        if (car != null && car.stolen()) throw ApiException.badRequest("This car is reported stolen.");
        boolean tracker = car != null && car.getLastSeenAt() != null && car.getLastSeenAt().isAfter(now().minusMinutes(10));
        LocalDateTime now = now();
        int mins = minutes != null ? minutes : l != null && l.getDriveMinutes() != null ? l.getDriveMinutes() : 30;
        b.setMinutesAllowed(mins);
        b.setRadiusM(radiusM != null ? radiusM : l != null && l.getDriveRadiusM() != null ? l.getDriveRadiusM() : 5000);
        if (b.getSpeedLimitKmh() == null) b.setSpeedLimitKmh(l != null && l.getSpeedLimitKmh() != null ? l.getSpeedLimitKmh() : 80);
        b.setTrackedBy(tracker ? "TRACKER" : "PHONE");
        double[] c = tracker ? new double[]{car.getLastLatitude(), car.getLastLongitude()}
                : lat != null && lon != null && validPoint(lat, lon) ? new double[]{lat, lon}
                : l != null ? new double[]{l.getLatitude(), l.getLongitude()}
                : car != null && car.getLastLatitude() != null ? new double[]{car.getLastLatitude(), car.getLastLongitude()}
                : null;
        if (c == null) throw ApiException.badRequest("Turn on location, so the drive knows where it starts.");
        b.setCenterLatitude(c[0]);
        b.setCenterLongitude(c[1]);
        b.setStatus("ACTIVE");
        b.setStartedAt(now);
        b.setDueBackAt(now.plusMinutes(mins));
        b.setMeetCode(null);
        bookings.save(b);
    }

    /** Either side: the car is back. */
    @Transactional
    public Map<String, Object> end(User user, Long id) {
        TestDriveBooking b = party(user, id);
        if (!b.active()) throw ApiException.badRequest("It isn't going on.");
        finish(b, now());
        afterEnd(b, user);
        return bookingView(b, user);
    }

    @Transactional
    public Map<String, Object> endForCar(User owner, Long carId) {
        TestDriveBooking b = bookings.findByCarIdAndStatus(carId, "ACTIVE").stream()
                .filter(x -> x.getSeller().getId().equals(owner.getId())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("No test drive is going on for this car."));
        finish(b, now());
        afterEnd(b, owner);
        return driveView(b);
    }

    public List<Map<String, Object>> drivesForCar(User owner, Long carId) {
        return bookings.findTop20ByCarIdAndSellerIdOrderByMeetAtDesc(carId, owner.getId()).stream()
                .filter(b -> b.active() || "ENDED".equals(b.getStatus())).map(this::driveView).toList();
    }

    /** The drive going on in this car right now, if any (Car guard shows it on the car). */
    public Map<String, Object> activeDriveForCar(Long carId) {
        return bookings.findByCarIdAndStatus(carId, "ACTIVE").stream().findFirst().map(this::driveView).orElse(null);
    }

    public User activeDriveSeller(Long carId) {
        return bookings.findByCarIdAndStatus(carId, "ACTIVE").stream().findFirst().map(TestDriveBooking::getSeller).orElse(null);
    }

    /** A Car guard car's open listing: {id, status, price} - or null. */
    public Map<String, Object> listingOfCar(Long carId) {
        return listings.findByCarIdAndStatusIn(carId, OPEN).stream().findFirst().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("status", l.getStatus());
            m.put("price", l.getPrice());
            return m;
        }).orElse(null);
    }

    /** A car handed to someone else, or removed: its listing comes off sale. */
    @Transactional
    public void carLeaving(Long carId) {
        for (MarketListing l : listings.findByCarIdAndStatusIn(carId, OPEN)) {
            for (TestDriveBooking b : bookings.findByListingIdOrderByMeetAtDesc(l.getId())) if (b.active()) finish(b, now());
            closeUpcoming(l, "the seller took it off sale");
            l.setStatus("WITHDRAWN");
            changed(listings.save(l), null);
        }
        for (TestDriveBooking b : bookings.findByCarIdAndStatus(carId, "ACTIVE")) finish(b, now());
    }

    /**
     * A car was reported stolen: any listing of it (linked, or with its
     * plate) comes off sale. Someone else selling it is a lead for the police.
     */
    @Transactional
    public void carStolen(GuardedCar car) {
        Set<MarketListing> found = new LinkedHashSet<>(listings.findByCarIdAndStatusIn(car.getId(), OPEN));
        if (car.getPlateKey() != null) found.addAll(listings.findByPlateKeyAndStatusIn(car.getPlateKey(), OPEN));
        for (MarketListing l : found) {
            for (TestDriveBooking b : bookings.findByListingIdOrderByMeetAtDesc(l.getId())) if (b.active()) finish(b, now());
            boolean someoneElse = !l.getSeller().getId().equals(car.getOwner().getId());
            closeUpcoming(l, someoneElse ? "it's reported stolen - don't buy it" : "it was reported stolen");
            l.setStatus("WITHDRAWN");
            changed(listings.save(l), null);
            if (someoneElse) {
                String who = l.getSeller().getName() + (l.getSeller().getPhone() != null ? " (" + l.getSeller().getPhone() + ")" : "");
                String where = l.getArea() != null ? " in " + l.getArea() : "";
                sightings.save(CarSighting.builder().carId(car.getId()).reporter(l.getSeller()).latitude(l.getLatitude())
                        .longitude(l.getLongitude()).note("Up for sale on Raksio Buy & Sell" + where).source("MARKET").at(now()).build());
                alerts.raiseAboutCar(car.getOwner(), RiskLevel.HIGH, CarGuardService.STOLEN_SEEN + ": your " + car.getName()
                        + " was up for sale on Raksio Buy & Sell" + where + ". The police have been told - don't go there yourself.",
                        l.getLatitude(), l.getLongitude(), car.getId(), car.getTheftIncidentId());
                if (car.getTheftIncidentId() != null) {
                    incidents.tellAssignee(car.getTheftIncidentId(), CarGuardService.STOLEN_SEEN + ": the " + car.describe()
                            + " was up for sale on Raksio Buy & Sell" + where + " by " + who + ".");
                }
            }
        }
        for (TestDriveBooking b : bookings.findByCarIdAndStatus(car.getId(), "ACTIVE")) finish(b, now());
    }

    private void afterEnd(TestDriveBooking b, User endedBy) {
        String summary = summary(b);
        User other = endedBy.getId().equals(b.getSeller().getId()) ? b.getBuyer() : b.getSeller();
        if (other != null) {
            tellAboutBooking(other, RiskLevel.LOW, TEST_DRIVE + ": the test drive of the " + titleOf(b) + " has ended. " + summary
                    + " Rate it in Raksio.", b);
        }
        changed(b.getListing(), b);
    }

    /** The phones in the car send where it is (when there's no GPS tracker). */
    @Transactional
    public Map<String, Object> phoneFix(User user, Long id, CarPointRequest p) {
        TestDriveBooking b = party(user, id);
        if (!b.active()) throw ApiException.badRequest("It isn't going on.");
        if (p.getLatitude() == null || p.getLongitude() == null || !validPoint(p.getLatitude(), p.getLongitude())) {
            throw ApiException.badRequest("That location doesn't look right.");
        }
        if ("PHONE".equals(b.getTrackedBy())) {
            Double speed = p.getSpeedKmh() != null && p.getSpeedKmh() >= 0 && p.getSpeedKmh() <= 300 ? p.getSpeedKmh() : null;
            driveFix(b, p.getLatitude(), p.getLongitude(), speed, now(), true);
        }
        return bookingView(b, user);
    }

    /** A phone's position at a given time - the same as phoneFix, for tests and the demo script. */
    @Transactional
    public void phoneFixAt(TestDriveBooking b, double lat, double lon, Double speedKmh, LocalDateTime at) {
        if (b.active() && "PHONE".equals(b.getTrackedBy())) driveFix(b, lat, lon, speedKmh, at, true);
    }

    /**
     * A Car guard tracker's position (CarGuardService.ingest). Returns true
     * when a test drive is going on in that car.
     */
    @Transactional
    public boolean carFix(Long carId, double lat, double lon, Double speed, LocalDateTime at, boolean fresh) {
        List<TestDriveBooking> active = bookings.findByCarIdAndStatus(carId, "ACTIVE");
        // Not on a test drive: it's out on the road, maybe with its owner - people looking for one like it nearby hear it's for sale.
        if (fresh && active.isEmpty()) {
            try {
                road.carMoved(carId, lat, lon, speed, at);
            } catch (RuntimeException e) {
                log.warn("For-sale-nearby check failed for car {}: {}", carId, e.getMessage());
            }
        }
        for (TestDriveBooking b : active) {
            // The tracker started sending during a drive the phones were following: it takes over (it's in the car for sure).
            if (!"TRACKER".equals(b.getTrackedBy()) && fresh) b.setTrackedBy("TRACKER");
            if ("TRACKER".equals(b.getTrackedBy())) driveFix(b, lat, lon, speed, at, fresh);
        }
        return !active.isEmpty();
    }

    /** Distance, top speed, over the limit, out of the area, back and parked. */
    void driveFix(TestDriveBooking b, double lat, double lon, Double speed, LocalDateTime at, boolean fresh) {
        LocalDateTime last = b.getLastAt();
        if (last != null && (at.isBefore(last) || Duration.between(last, at).toMillis() < 2000)) return;
        if (at.isBefore(b.getStartedAt().minusMinutes(1))) return;
        // The first position is measured from where it started.
        double pLat = b.getLastLatitude() != null ? b.getLastLatitude() : b.getCenterLatitude();
        double pLon = b.getLastLongitude() != null ? b.getLastLongitude() : b.getCenterLongitude();
        LocalDateTime from = last != null ? last : b.getStartedAt();
        double moved = GeoUtil.distanceMeters(pLat, pLon, lat, lon);
        long gapS = Math.max(1, Duration.between(from, at).getSeconds());
        if (speed == null && last != null && gapS <= 600) speed = Math.min(250, moved / gapS * 3.6);
        if (gapS <= 600 && moved < 10_000) b.setDistanceM(b.getDistanceM() + moved);
        int limit = b.getSpeedLimitKmh() != null ? b.getSpeedLimitKmh() : 80;
        String who = b.getBuyerName();
        if (speed != null && speed > b.getMaxSpeedKmh()) b.setMaxSpeedKmh(speed);
        if (speed != null && speed > limit && !Boolean.TRUE.equals(b.getOverLimitNow())) {
            b.setOverLimitNow(true);
            b.setOverLimitCount(b.getOverLimitCount() + 1);
            if (fresh) {
                tellAboutBooking(b.getSeller(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + who + " is doing " + Math.round(speed) + " km/h in the "
                        + titleOf(b) + " (limit " + limit + ").", b, lat, lon);
            }
        } else if (speed != null && speed < limit - 5) {
            b.setOverLimitNow(false); // counted again only after slowing down properly
        }
        double fromStart = GeoUtil.distanceMeters(b.getCenterLatitude(), b.getCenterLongitude(), lat, lon);
        if (fromStart > b.getRadiusM() && !Boolean.TRUE.equals(b.getLeftArea())) {
            b.setLeftArea(true);
            if (fresh) {
                tellAboutBooking(b.getSeller(), RiskLevel.HIGH, TEST_DRIVE + ": " + who + " has taken the " + titleOf(b) + " " + km(fromStart)
                        + " from the start (allowed " + km(b.getRadiusM()) + ").", b, lat, lon);
            }
        }
        // Standing still: where, since when.
        if (b.getStillLatitude() == null || GeoUtil.distanceMeters(b.getStillLatitude(), b.getStillLongitude(), lat, lon) > STILL_M) {
            b.setStillLatitude(lat);
            b.setStillLongitude(lon);
            b.setStillSince(at);
        }
        boolean parked = b.getStillSince() != null && !Duration.between(b.getStillSince(), at).minus(PARK_AFTER).isNegative();
        b.setLastLatitude(lat);
        b.setLastLongitude(lon);
        b.setLastSpeedKmh(speed);
        b.setLastAt(at);
        if (parked && fromStart <= BACK_M && b.getDistanceM() >= MIN_DRIVE_M) {
            // Back where it started and parked after a real drive: it ends by itself.
            finish(b, at);
            String text = TEST_DRIVE + ": " + who + " is back with the " + titleOf(b) + ". " + summary(b);
            tellAboutBooking(b.getSeller(), RiskLevel.LOW, text, b, lat, lon);
            if (b.getBuyer() != null) {
                tellAboutBooking(b.getBuyer(), RiskLevel.LOW, TEST_DRIVE + ": thanks for driving the " + titleOf(b) + ". " + summary(b)
                        + " Rate the seller in Raksio.", b);
            }
            changed(b.getListing(), b);
            return;
        }
        bookings.save(b);
        live(b);
    }

    private void finish(TestDriveBooking b, LocalDateTime at) {
        b.setStatus("ENDED");
        b.setEndedAt(at);
        b.setReturnedLate(b.getDueBackAt() != null && at.isAfter(b.getDueBackAt()));
        bookings.save(b);
    }

    /** After the drive: the buyer rates the seller and the vehicle, the seller rates the buyer's driving. Once each. */
    @Transactional
    public Map<String, Object> rate(User user, Long id, BookingActionRequest r) {
        TestDriveBooking b = party(user, id);
        if (!"ENDED".equals(b.getStatus())) throw ApiException.badRequest("Rate it once the test drive is over.");
        if (r == null || r.getStars() == null) throw ApiException.badRequest("Give 1 to 5 stars.");
        if (b.getSeller().getId().equals(user.getId())) {
            if (b.getSellerStars() != null) throw ApiException.badRequest("You've rated it already.");
            b.setSellerStars(r.getStars());
            b.setSellerComment(text(r.getComment()));
        } else {
            if (b.getBuyerStars() != null) throw ApiException.badRequest("You've rated it already.");
            b.setBuyerStars(r.getStars());
            b.setBuyerAsDescribed(r.getAsDescribed());
            b.setBuyerComment(text(r.getComment()));
        }
        bookings.save(b);
        changed(b.getListing(), b);
        return bookingView(b, user);
    }

    public Map<String, Object> booking(User user, Long id) {
        return bookingView(party(user, id), user);
    }

    // ------------------------------------------------------------------ every minute

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void tick() {
        try {
            checkBookings();
        } catch (RuntimeException e) {
            log.warn("Buy & Sell check failed: {}", e.toString());
        }
    }

    /**
     * Unanswered requests expire, agreed ones are reminded 30 minutes before
     * (and expire if they never met), late drives are reported once and
     * drives left running for hours are closed. Returns how many messages went.
     */
    @Transactional
    public int checkBookings() {
        LocalDateTime now = now();
        int n = 0;
        for (TestDriveBooking b : bookings.findByStatusIn(List.of("REQUESTED", "ACCEPTED", "ACTIVE"))) {
            switch (b.getStatus()) {
                case "REQUESTED" -> {
                    LocalDateTime t = b.getProposedAt() != null ? b.getProposedAt() : b.getMeetAt();
                    if (now.isAfter(t)) {
                        b.setStatus("EXPIRED");
                        bookings.save(b);
                        if (b.getBuyer() != null) {
                            tellAboutBooking(b.getBuyer(), RiskLevel.LOW, TEST_DRIVE + ": your request for the " + titleOf(b) + " "
                                    + when(t) + " wasn't settled in time. Ask for another time.", b);
                            n++;
                        }
                        changed(b.getListing(), b);
                    }
                }
                case "ACCEPTED" -> {
                    if (now.isAfter(b.getMeetAt().plus(START_AFTER))) {
                        b.setStatus("EXPIRED");
                        b.setAnswer("didn't happen");
                        bookings.save(b);
                        changed(b.getListing(), b);
                    } else if (!Boolean.TRUE.equals(b.getReminded()) && !now.isBefore(b.getMeetAt().minus(REMIND_BEFORE)) && now.isBefore(b.getMeetAt())) {
                        b.setReminded(true);
                        bookings.save(b);
                        String place = placeOf(b);
                        tellAboutBooking(b.getSeller(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + b.getBuyerName() + " is coming at "
                                + clock(b.getMeetAt()) + " for the " + titleOf(b) + ". Ask for their code to start.", b);
                        if (b.getBuyer() != null) {
                            tellAboutBooking(b.getBuyer(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + titleOf(b) + " at " + clock(b.getMeetAt()) + place
                                    + ". Meet in a public place, take your licence, and show your code to start.", b);
                        }
                        n += b.getBuyer() != null ? 2 : 1;
                    }
                }
                case "ACTIVE" -> {
                    if (b.getDueBackAt() == null) continue;
                    if (now.isAfter(b.getDueBackAt().plus(CLOSE_AFTER_DUE))) {
                        finish(b, now);
                        changed(b.getListing(), b);
                    } else if (now.isAfter(b.getDueBackAt()) && !Boolean.TRUE.equals(b.getLateAlerted())) {
                        b.setLateAlerted(true);
                        bookings.save(b);
                        String where = "";
                        if (b.getLastLatitude() != null) {
                            double d = GeoUtil.distanceMeters(b.getCenterLatitude(), b.getCenterLongitude(), b.getLastLatitude(), b.getLastLongitude());
                            where = " It's " + km(d) + " from the start.";
                        }
                        tellAboutBooking(b.getSeller(), RiskLevel.MEDIUM, TEST_DRIVE + ": " + b.getBuyerName() + " should have been back with the "
                                + titleOf(b) + " at " + clock(b.getDueBackAt()) + "." + where
                                + (b.getBuyerPhone() != null ? " Call them: " + b.getBuyerPhone() : ""), b, b.getLastLatitude(), b.getLastLongitude());
                        if (b.getBuyer() != null) {
                            tellAboutBooking(b.getBuyer(), RiskLevel.MEDIUM, TEST_DRIVE + ": time's up - please bring the " + titleOf(b) + " back.", b);
                        }
                        n++;
                    }
                }
                default -> {
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ views

    /** A test drive as one side sees it: the other side's number and the full plate only once it's agreed. */
    Map<String, Object> bookingView(TestDriveBooking b, User viewer) {
        boolean seller = b.getSeller().getId().equals(viewer.getId());
        boolean agreed = List.of("ACCEPTED", "ACTIVE", "ENDED").contains(b.getStatus());
        MarketListing l = b.getListing();
        Map<String, Object> m = driveView(b);
        m.put("role", seller ? "SELLER" : "BUYER");
        m.put("walkIn", b.getWalkIn());
        m.put("meetAt", b.getMeetAt());
        m.put("proposedAt", b.getProposedAt());
        m.put("waitingFor", waitingFor(b));
        m.put("note", b.getNote());
        m.put("driverLicence", b.getDriverLicence());
        m.put("answer", b.getAnswer());
        m.put("cancelledBy", b.getCancelledBy());
        m.put("trackedBy", b.getTrackedBy());
        m.put("meetCode", !seller && "ACCEPTED".equals(b.getStatus()) ? b.getMeetCode() : null);
        if (l != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", l.getId());
            v.put("title", l.title());
            v.put("price", l.getPrice());
            v.put("status", l.getStatus());
            v.put("area", l.getArea());
            v.put("vehicleType", l.getVehicleType());
            List<String> t = tokens(l);
            v.put("photo", t.isEmpty() ? null : photoPath(t.get(0)));
            v.put("plate", seller || agreed ? l.getPlate() : maskPlate(l.getPlateKey()));
            v.put("latitude", agreed || seller ? l.getLatitude() : null);
            v.put("longitude", agreed || seller ? l.getLongitude() : null);
            v.put("sellerType", l.getSellerType());
            v.put("businessName", l.getBusinessName());
            m.put("listing", v);
        } else {
            m.put("listing", null);
        }
        m.put("title", titleOf(b));
        if (seller) {
            m.put("otherName", b.getBuyerName());
            m.put("otherPhone", agreed || Boolean.TRUE.equals(b.getWalkIn()) ? b.getBuyerPhone() : null);
            m.put("other", b.getBuyer() != null ? buyerTrust(b.getBuyer()) : null);
        } else {
            m.put("otherName", l != null && l.getBusinessName() != null ? l.getBusinessName() + " (" + b.getSeller().getName() + ")" : b.getSeller().getName());
            m.put("otherPhone", agreed ? b.getSeller().getPhone() : null);
            m.put("other", sellerTrust(b.getSeller()));
        }
        if (b.active() && b.getLastLatitude() != null) {
            m.put("last", Map.of("latitude", b.getLastLatitude(), "longitude", b.getLastLongitude(), "at", b.getLastAt()));
        } else {
            m.put("last", null);
        }
        m.put("start", b.getCenterLatitude() != null ? Map.of("latitude", b.getCenterLatitude(), "longitude", b.getCenterLongitude()) : null);
        // Ratings: what I gave; what they gave me once I've rated too (so neither answers the other's).
        Integer mine = seller ? b.getSellerStars() : b.getBuyerStars();
        Integer theirs = seller ? b.getBuyerStars() : b.getSellerStars();
        m.put("myStars", mine);
        m.put("theirStars", mine != null ? theirs : null);
        m.put("theirComment", mine != null ? (seller ? b.getBuyerComment() : b.getSellerComment()) : null);
        m.put("asDescribed", b.getBuyerAsDescribed());
        m.put("canRate", "ENDED".equals(b.getStatus()) && mine == null && (seller ? b.getBuyer() != null || Boolean.TRUE.equals(b.getWalkIn()) : true));
        return m;
    }

    /** The drive itself - also what Car guard shows on a car (same shape as its old test drives). */
    Map<String, Object> driveView(TestDriveBooking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("status", b.getStatus());
        m.put("customerName", b.getBuyerName());
        m.put("customerPhone", b.getBuyerPhone());
        m.put("startedAt", b.getStartedAt());
        m.put("dueBackAt", b.getDueBackAt());
        m.put("endedAt", b.getEndedAt());
        m.put("radiusM", b.getRadiusM());
        m.put("minutesAllowed", b.getMinutesAllowed());
        m.put("distanceKm", Math.round(b.getDistanceM() / 100.0) / 10.0);
        m.put("maxSpeedKmh", (int) Math.round(b.getMaxSpeedKmh()));
        m.put("speedLimitKmh", b.getSpeedLimitKmh() != null ? b.getSpeedLimitKmh() : 80);
        m.put("overLimitCount", b.getOverLimitCount());
        m.put("leftArea", b.getLeftArea());
        m.put("late", b.active() && b.getDueBackAt() != null ? now().isAfter(b.getDueBackAt()) : Boolean.TRUE.equals(b.getReturnedLate()));
        if (b.getStartedAt() != null) {
            LocalDateTime end = b.getEndedAt() != null ? b.getEndedAt() : now();
            m.put("minutes", Math.max(0, Duration.between(b.getStartedAt(), end).toMinutes()));
            if (b.active() && b.getDueBackAt() != null) m.put("minutesLeft", Duration.between(now(), b.getDueBackAt()).toMinutes());
            m.put("verdict", verdict(b));
            m.put("summary", summary(b));
        } else {
            m.put("minutes", 0);
            m.put("verdict", null);
            m.put("summary", null);
        }
        return m;
    }

    /** CAREFUL (nothing to note), CHECK (a little fast, or late), RISKY (often fast, far over, or left the area). */
    static String verdict(TestDriveBooking b) {
        int limit = b.getSpeedLimitKmh() != null ? b.getSpeedLimitKmh() : 80;
        boolean late = Boolean.TRUE.equals(b.getReturnedLate()) || Boolean.TRUE.equals(b.getLateAlerted());
        if (b.getOverLimitCount() >= 3 || b.getMaxSpeedKmh() >= limit + 30 || Boolean.TRUE.equals(b.getLeftArea())) return "RISKY";
        if (b.getOverLimitCount() > 0 || late) return "CHECK";
        return "CAREFUL";
    }

    /** "12.4 km in 26 min, top speed 84 km/h, over 80 km/h once, stayed within 5 km, back on time." */
    static String summary(TestDriveBooking b) {
        int limit = b.getSpeedLimitKmh() != null ? b.getSpeedLimitKmh() : 80;
        LocalDateTime end = b.getEndedAt() != null ? b.getEndedAt() : LocalDateTime.now();
        long minutes = b.getStartedAt() != null ? Math.max(0, Duration.between(b.getStartedAt(), end).toMinutes()) : 0;
        int over = b.getOverLimitCount();
        StringBuilder s = new StringBuilder();
        s.append(Math.round(b.getDistanceM() / 100.0) / 10.0).append(" km in ").append(minutes).append(" min, top speed ")
                .append(Math.round(b.getMaxSpeedKmh())).append(" km/h, ")
                .append(over == 0 ? "never over " + limit + " km/h" : "over " + limit + " km/h " + (over == 1 ? "once" : over + " times"))
                .append(", ").append(Boolean.TRUE.equals(b.getLeftArea()) ? "went beyond " : "stayed within ")
                .append(km(b.getRadiusM() != null ? b.getRadiusM() : 5000));
        if (b.getEndedAt() != null) s.append(Boolean.TRUE.equals(b.getReturnedLate()) ? ", back late." : ", back on time.");
        else s.append(".");
        return s.toString();
    }

    /** SELLER (a request to answer), BUYER (a new time to agree to), or "". */
    static String waitingFor(TestDriveBooking b) {
        if (!"REQUESTED".equals(b.getStatus())) return "";
        return b.getProposedAt() != null ? "BUYER" : "SELLER";
    }

    /** What buyers see about a seller. */
    Map<String, Object> sellerTrust(User u) {
        List<TestDriveBooking> drives = bookings.findBySellerIdOrderByMeetAtDesc(u.getId());
        Map<String, Object> m = trustBase(u);
        m.put("sales", listings.countBySellerIdAndStatus(u.getId(), "SOLD"));
        m.put("drives", drives.stream().filter(b -> "ENDED".equals(b.getStatus())).count());
        double[] stars = drives.stream().filter(b -> b.getBuyerStars() != null).mapToDouble(TestDriveBooking::getBuyerStars).toArray();
        m.put("rating", stars.length > 0 ? Math.round(Arrays.stream(stars).average().orElse(0) * 10) / 10.0 : null);
        m.put("ratings", stars.length);
        long said = drives.stream().filter(b -> b.getBuyerAsDescribed() != null).count();
        long yes = drives.stream().filter(b -> Boolean.TRUE.equals(b.getBuyerAsDescribed())).count();
        m.put("asDescribedPct", said > 0 ? Math.round(yes * 100.0 / said) : null);
        return m;
    }

    /** What a seller sees about a buyer who asks for a test drive. */
    Map<String, Object> buyerTrust(User u) {
        List<TestDriveBooking> drives = bookings.findByBuyerIdOrderByMeetAtDesc(u.getId());
        Map<String, Object> m = trustBase(u);
        m.put("drives", drives.stream().filter(b -> "ENDED".equals(b.getStatus())).count());
        double[] stars = drives.stream().filter(b -> b.getSellerStars() != null).mapToDouble(TestDriveBooking::getSellerStars).toArray();
        m.put("rating", stars.length > 0 ? Math.round(Arrays.stream(stars).average().orElse(0) * 10) / 10.0 : null);
        m.put("ratings", stars.length);
        // Agreed, then never turned up (or called off within two hours).
        m.put("missed", drives.stream().filter(b -> ("EXPIRED".equals(b.getStatus()) && "didn't happen".equals(b.getAnswer()))
                || ("CANCELLED".equals(b.getStatus()) && "BUYER".equals(b.getCancelledBy()) && b.getMeetCode() != null
                && b.getUpdatedAt() != null && b.getUpdatedAt().isAfter(b.getMeetAt().minusHours(2)))).count());
        return m;
    }

    private Map<String, Object> trustBase(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("name", u.getName());
        m.put("memberSince", u.getCreatedAt());
        m.put("phoneSaved", u.getPhone() != null && !u.getPhone().isBlank());
        m.put("points", u.getRewardPoints() != null ? u.getRewardPoints() : 0);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private MarketListing mine(User seller, Long id) {
        MarketListing l = listings.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
        if (!l.getSeller().getId().equals(seller.getId())) throw ApiException.notFound("Not found");
        return l;
    }

    private TestDriveBooking party(User user, Long id) {
        TestDriveBooking b = bookings.findById(id).orElseThrow(() -> ApiException.notFound("Test drive not found"));
        boolean in = b.getSeller().getId().equals(user.getId()) || (b.getBuyer() != null && b.getBuyer().getId().equals(user.getId()));
        if (!in) throw ApiException.notFound("Test drive not found");
        return b;
    }

    private TestDriveBooking asSeller(User user, Long id) {
        TestDriveBooking b = party(user, id);
        if (!b.getSeller().getId().equals(user.getId())) throw ApiException.forbidden("Only the seller can do that.");
        return b;
    }

    private TestDriveBooking asBuyer(User user, Long id) {
        TestDriveBooking b = party(user, id);
        if (b.getBuyer() == null || !b.getBuyer().getId().equals(user.getId())) throw ApiException.forbidden("Only the buyer can do that.");
        return b;
    }

    /** Another agreed drive of the same vehicle too close to this time. */
    private void clash(TestDriveBooking b, LocalDateTime at) {
        if (b.getListing() == null) return;
        long gap = (b.getListing().getDriveMinutes() != null ? b.getListing().getDriveMinutes() : 30) + 15;
        for (TestDriveBooking o : bookings.findByListingIdOrderByMeetAtDesc(b.getListing().getId())) {
            if (o.getId().equals(b.getId()) || !"ACCEPTED".equals(o.getStatus())) continue;
            if (Math.abs(Duration.between(o.getMeetAt(), at).toMinutes()) < gap) {
                throw ApiException.badRequest(o.getBuyerName() + " is coming at " + clock(o.getMeetAt()) + " - pick a time at least "
                        + gap + " minutes apart.");
            }
        }
    }

    /** 30 minutes to 14 days ahead, starting 7 am to 8 pm (India time). */
    static LocalDateTime checkTime(LocalDateTime at) {
        if (at == null) throw ApiException.badRequest("Pick a day and time.");
        LocalDateTime now = now();
        if (at.isBefore(now.plus(MIN_NOTICE))) throw ApiException.badRequest("Pick a time at least 30 minutes from now.");
        if (at.isAfter(now.plus(MAX_AHEAD))) throw ApiException.badRequest("Pick a day within the next two weeks.");
        if (at.getHour() < OPEN_HOUR || at.getHour() >= CLOSE_HOUR) {
            throw ApiException.badRequest("Test drives are between 7 am and 8 pm - in daylight, for everyone's safety.");
        }
        return at.withSecond(0).withNano(0);
    }

    private User findAccount(String to) {
        String t = to.trim();
        if (t.contains("@")) return users.findByEmail(t.toLowerCase(Locale.ROOT)).or(() -> users.findByEmail(t)).orElse(null);
        return users.findByPhone(ContactService.normalise(t)).stream().findFirst().orElse(null);
    }

    private void tellAboutBooking(User to, RiskLevel level, String text, TestDriveBooking b) {
        tellAboutBooking(to, level, text, b, null, null);
    }

    private void tellAboutBooking(User to, RiskLevel level, String text, TestDriveBooking b, Double lat, Double lon) {
        if (to == null) return;
        alerts.raiseAboutMarket(to, level, text, lat, lon, b.getListing() != null ? b.getListing().getId() : null, b.getId());
    }

    /** Tells the apps involved to refresh (no details - they fetch what they may see). */
    private void changed(MarketListing l, TestDriveBooking b) {
        Map<String, Object> m = new HashMap<>();
        m.put("listingId", l != null ? l.getId() : null);
        m.put("bookingId", b != null ? b.getId() : null);
        m.put("sellerId", l != null ? l.getSeller().getId() : b != null ? b.getSeller().getId() : null);
        m.put("buyerId", b != null && b.getBuyer() != null ? b.getBuyer().getId() : null);
        webSocket.broadcast("MARKET", m);
    }

    /** During a drive: only the two people in it refresh. */
    private void live(TestDriveBooking b) {
        changed(b.getListing(), b);
    }

    private String titleOf(TestDriveBooking b) {
        if (b.getListing() != null) return b.getListing().title();
        return b.getCarId() != null ? cars.findById(b.getCarId()).map(GuardedCar::getName).orElse("car") : "vehicle";
    }

    private static String placeOf(TestDriveBooking b) {
        return b.getListing() != null && b.getListing().getArea() != null ? ", at " + b.getListing().getArea() : "";
    }

    static String photoPath(String token) {
        return "/api/market/photo/" + token;
    }

    /** "AP16BX2231" -> "AP16 •• 2231" (where it's from and the last digits - enough to recognise, not to misuse). */
    static String maskPlate(String key) {
        if (key == null) return null;
        if (key.length() <= 6) return key.substring(0, 2) + " ••••";
        return key.substring(0, 4) + " •• " + key.substring(key.length() - 4);
    }

    /** "on Sat 9 Nov at 10:30 am" - "today at ..." / "tomorrow at ..." when it is. */
    static String when(LocalDateTime at) {
        LocalDate d = at.toLocalDate();
        LocalDate today = now().toLocalDate();
        String day = d.equals(today) ? "today" : d.equals(today.plusDays(1)) ? "tomorrow" : "on " + at.format(DAY);
        return day + " at " + clock(at);
    }

    static String clock(LocalDateTime at) {
        return at.format(CLOCK).toLowerCase(Locale.ROOT);
    }

    /** ₹3,45,000 (Indian grouping). */
    static String rupees(long amount) {
        String s = Long.toString(Math.abs(amount));
        if (s.length() > 3) {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3);
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < rest.length(); i++) {
                if (i > 0 && (rest.length() - i) % 2 == 0) r.append(',');
                r.append(rest.charAt(i));
            }
            s = r + "," + last3;
        }
        return (amount < 0 ? "-₹" : "₹") + s;
    }

    static String km(double meters) {
        return CarGuardService.km(meters);
    }

    private static String code() {
        return String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
    }

    private static String random(int n) {
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return s.toString();
    }

    private static String oneOf(String value, Set<String> allowed, String message) {
        if (value == null) throw ApiException.badRequest(message);
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) throw ApiException.badRequest(message);
        return v;
    }

    private static String required(String s, String message) {
        String t = text(s);
        if (t == null) throw ApiException.badRequest(message);
        return t;
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean validPoint(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !Double.isNaN(lat) && !Double.isNaN(lon)
                && !(Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001);
    }

    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
