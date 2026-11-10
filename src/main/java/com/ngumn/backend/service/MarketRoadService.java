package com.ngumn.backend.service;

import com.ngumn.backend.dto.WishRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.*;
import com.ngumn.backend.util.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Looking for" and "for sale near you" - Buy &amp; Sell on the road.
 *
 * <pre>
 *   Looking for   a buyer saves what they want (a car under 6 lakh, a Swift,
 *                 a bike from a dealer...). Up to 5.
 *   New listing   someone puts one up for sale -> they're told.
 *   On the road   the seller drives the vehicle they're selling (their Raksio
 *                 number plate is its plate) or its Car guard tracker says
 *                 it's moving: people looking for one like it within about
 *                 150 m - next to it at a signal, behind it - are told "the
 *                 red Swift next to you is for sale". It works the other way
 *                 round too: as the buyer moves, vehicles for sale around them
 *                 are checked.
 *   Passing by    a buyer passes within 200 m of a dealer or showroom that has
 *                 vehicles they're looking for -> one note for that shop.
 *                 (A private seller's place is often their home, so it's only
 *                 used while they drive it.)
 * </pre>
 *
 * These are quiet notes ("Have a look once you've stopped") - never spoken,
 * never in the way while someone drives. Each vehicle is told to each buyer
 * at most once a week, and nobody gets more than five a day. Sellers see how
 * many interested buyers it reached.
 */
@Service
public class MarketRoadService {

    public static final String NEAR = "For sale nearby";
    public static final String LOOKING = "Looking for";

    static final int MAX_WISHES = 5;
    /** Next to each other on the road. */
    static final double NEAR_M = 150;
    /** Passing a dealer or showroom. */
    static final double PASS_M = 200;
    /** A position this old isn't "now" any more. */
    static final long LIVE_S = 120;
    /** Moving: faster than this (a for-sale vehicle standing at home isn't announced). */
    static final double MOVING_KMH = 8;
    static final Duration AGAIN_AFTER = Duration.ofDays(7);
    static final int PER_DAY = 5;
    /** A buyer's surroundings are checked at most this often. */
    static final Duration LOOK_EVERY = Duration.ofSeconds(20);

    private static final Logger log = LoggerFactory.getLogger(MarketRoadService.class);

    private final MarketListingRepository listings;
    private final MarketWishRepository wishes;
    private final ListingSightingRepository sightings;
    private final VehicleRepository vehicles;
    private final AlertService alerts;
    private final MarketArea area;
    private final Map<Long, LocalDateTime> lastLook = new ConcurrentHashMap<>();

    public MarketRoadService(MarketListingRepository listings, MarketWishRepository wishes, ListingSightingRepository sightings,
                             VehicleRepository vehicles, AlertService alerts, MarketArea area) {
        this.listings = listings;
        this.wishes = wishes;
        this.sightings = sightings;
        this.vehicles = vehicles;
        this.alerts = alerts;
        this.area = area;
    }

    // ------------------------------------------------------------------ Looking for

    public List<Map<String, Object>> myWishes(User user) {
        return wishes.findByUserIdOrderByCreatedAtAsc(user.getId()).stream().map(this::wishView).toList();
    }

    public Map<String, Object> addWish(User user, WishRequest r) {
        if (wishes.findByUserIdOrderByCreatedAtAsc(user.getId()).size() >= MAX_WISHES) {
            throw ApiException.badRequest("You can look for " + MAX_WISHES + " things at a time - remove one first.");
        }
        MarketWish w = MarketWish.builder().user(user).build();
        apply(w, r);
        if (w.getVehicleType() == null && w.getQ() == null && w.getMaxPrice() == null) {
            throw ApiException.badRequest("Say what you're looking for - a kind of vehicle, a model, or a budget.");
        }
        return wishView(wishes.save(w));
    }

    public Map<String, Object> updateWish(User user, Long id, WishRequest r) {
        MarketWish w = mine(user, id);
        apply(w, r);
        return wishView(wishes.save(w));
    }

    public void deleteWish(User user, Long id) {
        wishes.delete(mine(user, id));
    }

    private MarketWish mine(User user, Long id) {
        MarketWish w = wishes.findById(id).orElseThrow(() -> ApiException.notFound("Not found"));
        if (!w.getUser().getId().equals(user.getId())) throw ApiException.notFound("Not found");
        return w;
    }

    private void apply(MarketWish w, WishRequest r) {
        if (r.getVehicleType() != null) w.setVehicleType(pick(r.getVehicleType(), MarketService.VEHICLE_TYPES, "Kind: car, bike, scooter, auto or commercial."));
        if (r.getQ() != null) w.setQ(r.getQ().isBlank() ? null : r.getQ().trim());
        if (r.getMinPrice() != null) w.setMinPrice(r.getMinPrice() > 0 ? r.getMinPrice() : null);
        if (r.getMaxPrice() != null) w.setMaxPrice(r.getMaxPrice() > 0 ? r.getMaxPrice() : null);
        if (r.getMaxKm() != null) w.setMaxKm(r.getMaxKm() > 0 ? r.getMaxKm() : null);
        if (r.getMinYear() != null) w.setMinYear(r.getMinYear());
        if (r.getFuel() != null) w.setFuel(pick(r.getFuel(), MarketService.FUELS, "Fuel: petrol, diesel, CNG, electric, hybrid or LPG."));
        if (r.getTransmission() != null) w.setTransmission(pick(r.getTransmission(), MarketService.TRANSMISSIONS, "Manual or automatic."));
        if (r.getSellerType() != null) w.setSellerType(pick(r.getSellerType(), Set.of("BUSINESS", "PRIVATE"), "Dealers or owners."));
        if (r.getCondition() != null) w.setCondition(pick(r.getCondition(), Set.of("USED", "NEW"), "New or used."));
        if (r.getOnRoad() != null) w.setOnRoad(r.getOnRoad());
        if (r.getNewListings() != null) w.setNewListings(r.getNewListings());
        if (r.getActive() != null) w.setActive(r.getActive());
        if (w.getMinPrice() != null && w.getMaxPrice() != null && w.getMinPrice() > w.getMaxPrice()) {
            throw ApiException.badRequest("The lowest price is above the highest.");
        }
    }

    private static String pick(String v, Set<String> allowed, String message) {
        if (v.isBlank()) return null;
        String u = v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw ApiException.badRequest(message);
        return u;
    }

    Map<String, Object> wishView(MarketWish w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("label", w.describe());
        m.put("vehicleType", w.getVehicleType());
        m.put("q", w.getQ());
        m.put("minPrice", w.getMinPrice());
        m.put("maxPrice", w.getMaxPrice());
        m.put("maxKm", w.getMaxKm());
        m.put("minYear", w.getMinYear());
        m.put("fuel", w.getFuel());
        m.put("transmission", w.getTransmission());
        m.put("sellerType", w.getSellerType());
        m.put("condition", w.getCondition());
        m.put("onRoad", w.getOnRoad());
        m.put("newListings", w.getNewListings());
        m.put("active", w.getActive());
        m.put("forSaleNow", listings.findByStatusIn(MarketService.OPEN).stream()
                .filter(l -> !l.getSeller().getId().equals(w.getUser().getId())).filter(w::matches).count());
        return m;
    }

    // ------------------------------------------------------------------ a new listing

    /** Someone put a vehicle up for sale: people looking for one like it hear about it. Returns how many. */
    public int newListing(MarketListing l) {
        int told = 0;
        Set<Long> done = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (MarketWish w : wishes.findByActiveTrue()) {
            User u = w.getUser();
            if (!Boolean.TRUE.equals(w.getNewListings()) || u.getId().equals(l.getSeller().getId()) || done.contains(u.getId())) continue;
            if (!w.matches(l) || !underCap(u, now)) continue;
            done.add(u.getId());
            sightings.save(ListingSighting.builder().listingId(l.getId()).buyerId(u.getId()).kind("NEW").at(now).build());
            String where = l.getArea() != null ? ", " + l.getArea() : "";
            alerts.raiseAboutMarket(u, RiskLevel.LOW, LOOKING + ": " + article(l) + " " + l.title() + " is now for sale - "
                    + MarketWish.lakh(l.getPrice()) + where + ". It matches \"" + w.describe() + "\".", null, null, l.getId(), null);
            told++;
        }
        return told;
    }

    // ------------------------------------------------------------------ on the road

    /**
     * A Raksio user's position (VehicleService.updateLocation): if they're
     * driving a vehicle they're selling, people looking for one nearby are
     * told; if they're looking for one, what's for sale around them is checked.
     */
    public void vehicleMoved(Vehicle v) {
        try {
            if (v.getCurrentLatitude() == null || v.getOwner() == null || Boolean.TRUE.equals(v.getIsSimulated())) return;
            LocalDateTime now = LocalDateTime.now();
            String key = CarGuardService.plateKey(v.getPlateNumber());
            if (key != null) {
                for (MarketListing l : listings.findByPlateKeyAndStatusIn(key, MarketService.OPEN)) {
                    if (!l.getSeller().getId().equals(v.getOwner().getId())) continue;
                    double speed = v.getSpeedKmh() != null ? v.getSpeedKmh() : 0;
                    if (onRoad(l, v.getCurrentLatitude(), v.getCurrentLongitude(), speed, now)) tellBuyersNear(l, now);
                }
            }
            lookAround(v.getOwner(), v, now);
        } catch (RuntimeException e) {
            log.warn("Buy & Sell road check failed: {}", e.toString());
        }
    }

    /** A Car guard tracker in a car that's for sale (MarketService.carFix). */
    public void carMoved(Long carId, double lat, double lon, Double speed, LocalDateTime at) {
        try {
            LocalDateTime now = LocalDateTime.now();
            if (at.isBefore(now.minusSeconds(LIVE_S))) return;
            for (MarketListing l : listings.findByCarIdAndStatusIn(carId, MarketService.OPEN)) {
                if (onRoad(l, lat, lon, speed != null ? speed : 0, at)) tellBuyersNear(l, now);
            }
        } catch (RuntimeException e) {
            log.warn("Buy & Sell road check failed: {}", e.toString());
        }
    }

    /** Records where it is while it moves. True when it's moving (and the seller lets it be announced). */
    private boolean onRoad(MarketListing l, double lat, double lon, double speed, LocalDateTime at) {
        if (!Boolean.TRUE.equals(l.getShowOnRoad()) || !area.contains(lat, lon)) return false;
        if (speed < MOVING_KMH) {
            // Stopped at a signal: still "on the road" for a short while; parked at home: not announced.
            return l.getRoadAt() != null && l.getRoadAt().isAfter(at.minusSeconds(LIVE_S))
                    && GeoUtil.distanceMeters(l.getRoadLatitude(), l.getRoadLongitude(), lat, lon) < 60;
        }
        l.setRoadLatitude(lat);
        l.setRoadLongitude(lon);
        l.setRoadSpeedKmh(speed);
        l.setRoadAt(at);
        listings.save(l);
        return true;
    }

    /** People looking for one like it, around it right now. */
    private void tellBuyersNear(MarketListing l, LocalDateTime now) {
        Map<Long, MarketWish> wanting = new HashMap<>();
        for (MarketWish w : wishes.findByActiveTrue()) {
            if (Boolean.TRUE.equals(w.getOnRoad()) && !w.getUser().getId().equals(l.getSeller().getId()) && w.matches(l)) {
                wanting.putIfAbsent(w.getUser().getId(), w);
            }
        }
        if (wanting.isEmpty()) return;
        for (Vehicle v : vehicles.findByStatus(VehicleStatus.ACTIVE)) {
            if (v.getOwner() == null || !wanting.containsKey(v.getOwner().getId()) || !v.seenWithin(now, LIVE_S)) continue;
            double d = GeoUtil.distanceMeters(l.getRoadLatitude(), l.getRoadLongitude(), v.getCurrentLatitude(), v.getCurrentLongitude());
            if (d <= NEAR_M) tellOnRoad(v.getOwner(), l, d, now);
        }
    }

    /** What's for sale around a buyer: moving next to them, or at a dealer they're passing. */
    private void lookAround(User buyer, Vehicle v, LocalDateTime now) {
        LocalDateTime last = lastLook.get(buyer.getId());
        if (last != null && last.isAfter(now.minus(LOOK_EVERY))) return;
        List<MarketWish> mine = wishes.findByUserIdOrderByCreatedAtAsc(buyer.getId()).stream()
                .filter(w -> Boolean.TRUE.equals(w.getActive()) && Boolean.TRUE.equals(w.getOnRoad())).toList();
        if (mine.isEmpty()) return;
        lastLook.put(buyer.getId(), now);
        double lat = v.getCurrentLatitude(), lon = v.getCurrentLongitude();
        if (!area.contains(lat, lon)) return;
        Map<Long, List<MarketListing>> atShops = new LinkedHashMap<>();
        for (MarketListing l : listings.findByStatusIn(MarketService.OPEN)) {
            if (l.getSeller().getId().equals(buyer.getId()) || mine.stream().noneMatch(w -> w.matches(l))) continue;
            boolean moving = l.getRoadAt() != null && l.getRoadAt().isAfter(now.minusSeconds(LIVE_S));
            if (moving) {
                double d = GeoUtil.distanceMeters(l.getRoadLatitude(), l.getRoadLongitude(), lat, lon);
                if (d <= NEAR_M) {
                    tellOnRoad(buyer, l, d, now);
                    continue;
                }
            }
            boolean shop = "DEALER".equals(l.getSellerType()) || "SHOWROOM".equals(l.getSellerType());
            if (shop && GeoUtil.distanceMeters(l.getLatitude(), l.getLongitude(), lat, lon) <= PASS_M
                    && !sightings.existsByListingIdAndBuyerIdAndAtAfter(l.getId(), buyer.getId(), now.minus(AGAIN_AFTER))) {
                atShops.computeIfAbsent(l.getSeller().getId(), k -> new ArrayList<>()).add(l);
            }
        }
        for (List<MarketListing> shop : atShops.values()) {
            if (!underCap(buyer, now)) return;
            shop.sort(Comparator.comparing(MarketListing::getPrice));
            MarketListing first = shop.get(0);
            double d = GeoUtil.distanceMeters(first.getLatitude(), first.getLongitude(), lat, lon);
            for (MarketListing l : shop) {
                sightings.save(ListingSighting.builder().listingId(l.getId()).buyerId(buyer.getId()).kind("PLACE").distanceM(d).at(now).build());
            }
            String name = first.getBusinessName() != null ? first.getBusinessName() : first.getSeller().getName();
            String text = shop.size() == 1
                    ? NEAR + ": " + name + ", " + Math.round(d / 10) * 10 + " m from you, has the " + first.title() + " for "
                    + MarketWish.lakh(first.getPrice()) + ". Have a look once you've stopped."
                    : NEAR + ": " + name + ", " + Math.round(d / 10) * 10 + " m from you, has " + shop.size()
                    + " vehicles you're looking for, from " + MarketWish.lakh(first.getPrice()) + ". Have a look once you've stopped.";
            alerts.raiseAboutMarket(buyer, RiskLevel.LOW, text, null, null, first.getId(), null);
        }
    }

    private void tellOnRoad(User buyer, MarketListing l, double d, LocalDateTime now) {
        if (sightings.existsByListingIdAndBuyerIdAndAtAfter(l.getId(), buyer.getId(), now.minus(AGAIN_AFTER))) return;
        if (!underCap(buyer, now)) return;
        sightings.save(ListingSighting.builder().listingId(l.getId()).buyerId(buyer.getId()).kind("ROAD").distanceM(d).at(now).build());
        String colour = l.getColour() != null ? l.getColour().toLowerCase(Locale.ROOT) + " " : "";
        String plate = l.getPlateKey() != null ? " (" + MarketService.maskPlate(l.getPlateKey()) + ")" : "";
        alerts.raiseAboutMarket(buyer, RiskLevel.LOW, NEAR + ": the " + colour + l.title() + plate + " on the road next to you is for sale - "
                + MarketWish.lakh(l.getPrice()) + ". Have a look once you've stopped.", null, null, l.getId(), null);
    }

    private boolean underCap(User u, LocalDateTime now) {
        return sightings.countByBuyerIdAndAtAfter(u.getId(), now.minusHours(24)) < PER_DAY;
    }

    /** For the seller: how many people looking for one like it were told, and how. */
    public Map<String, Object> reach(Long listingId) {
        List<ListingSighting> s = sightings.findByListingId(listingId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("road", s.stream().filter(x -> "ROAD".equals(x.getKind())).map(ListingSighting::getBuyerId).distinct().count());
        m.put("place", s.stream().filter(x -> "PLACE".equals(x.getKind())).map(ListingSighting::getBuyerId).distinct().count());
        m.put("matched", s.stream().map(ListingSighting::getBuyerId).distinct().count());
        return m;
    }

    /** "a" / "an" before the title ("an Innova"... the title starts with the year, so mostly "a"). */
    private static String article(MarketListing l) {
        String t = l.title().toLowerCase(Locale.ROOT);
        return t.matches("^(8|11|18|[aeio]).*") ? "an" : "a";
    }
}
