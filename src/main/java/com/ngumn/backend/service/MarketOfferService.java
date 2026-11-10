package com.ngumn.backend.service;

import com.ngumn.backend.dto.ListingRequest;
import com.ngumn.backend.dto.OfferRequest;
import com.ngumn.backend.entity.*;
import com.ngumn.backend.exception.ApiException;
import com.ngumn.backend.repository.MarketListingRepository;
import com.ngumn.backend.repository.MarketOfferRepository;
import com.ngumn.backend.websocket.NgumnWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Offers in Buy &amp; Sell - how showrooms, dealers and owners actually
 * agree a deal:
 *
 * <pre>
 *   PRICE      "I'll pay ₹4.2 lakh" - on a negotiable vehicle, at least half
 *              the asking price.
 *   EXCHANGE   "Take my 2022 Activa as part of it" - where the seller takes
 *              exchange (usual for showrooms and dealers). The vehicle's
 *              number plate is checked against stolen ones, like a listing:
 *              nobody can trade in a stolen vehicle; its owner and the police
 *              hear who tried.
 *   Seller     accept / counter (a price, or what the exchanged vehicle is
 *              worth to them) / decline. Accepted: it's reserved for that
 *              buyer (who can still book a test drive), with what they pay.
 *   Sold       on the offer (MarketService.markSold): the other offers close;
 *              a dealer or showroom adds the exchanged vehicle to their stock
 *              in one tap - it goes up for sale with its details filled in.
 * </pre>
 */
@Service
public class MarketOfferService {

    public static final String OFFER = "Offer";

    private final MarketListingRepository listings;
    private final MarketOfferRepository offers;
    private final MarketService market;
    private final AlertService alerts;
    private final NgumnWebSocketHandler webSocket;

    public MarketOfferService(MarketListingRepository listings, MarketOfferRepository offers, MarketService market,
                              AlertService alerts, NgumnWebSocketHandler webSocket) {
        this.listings = listings;
        this.offers = offers;
        this.market = market;
        this.alerts = alerts;
        this.webSocket = webSocket;
    }

    /**
     * The buyer's offer. Not one transaction on purpose: a stolen exchange
     * vehicle's sighting and messages are kept although the offer is refused.
     */
    public Map<String, Object> make(User buyer, Long listingId, OfferRequest r) {
        MarketListing l = listings.findById(listingId).orElseThrow(() -> ApiException.notFound("This vehicle isn't for sale any more."));
        if (l.getSeller().getId().equals(buyer.getId())) throw ApiException.badRequest("That's your own vehicle.");
        if (!"ACTIVE".equals(l.getStatus())) {
            throw ApiException.badRequest("RESERVED".equals(l.getStatus()) ? "It's reserved for another buyer." : "It isn't for sale any more.");
        }
        if (buyer.getPhone() == null || buyer.getPhone().isBlank()) {
            throw ApiException.badRequest("Add your mobile number in Profile first, so the seller can reach you.");
        }
        boolean open = offers.findByListingIdOrderByCreatedAtDesc(l.getId()).stream()
                .anyMatch(o -> o.getBuyer().getId().equals(buyer.getId()) && (o.open() || "ACCEPTED".equals(o.getStatus())));
        if (open) throw ApiException.badRequest("You have an offer on it already - withdraw that one to make a new one.");
        String kind = r.getKind() == null ? "PRICE" : r.getKind().trim().toUpperCase(Locale.ROOT);
        MarketOffer o = MarketOffer.builder().listing(l).buyer(buyer).status("OPEN").note(text(r.getNote())).build();
        String text;
        if (kind.equals("PRICE")) {
            if (!Boolean.TRUE.equals(l.getNegotiable())) throw ApiException.badRequest("The price is fixed - book a test drive or call the seller.");
            if (r.getPrice() == null) throw ApiException.badRequest("How much do you offer?");
            if (r.getPrice() > l.getPrice()) throw ApiException.badRequest("That's more than the asking price.");
            if (r.getPrice() < l.getPrice() / 2) throw ApiException.badRequest("That's less than half the asking price - make a real offer.");
            o.setKind("PRICE");
            o.setPrice(r.getPrice());
            text = OFFER + ": " + buyer.getName() + " offers " + MarketService.rupees(r.getPrice()) + " for your " + l.title()
                    + " (asking " + MarketService.rupees(l.getPrice()) + ").";
        } else if (kind.equals("EXCHANGE")) {
            if (!Boolean.TRUE.equals(l.getExchange())) throw ApiException.badRequest("The seller doesn't take exchange for this one.");
            o.setKind("EXCHANGE");
            exchangeVehicle(o, buyer, r);
            if (o.getExPlateKey().equals(l.getPlateKey())) throw ApiException.badRequest("That's the same vehicle.");
            market.checkNotStolen(buyer, o.getExPlateKey(), o.getExPlate(), l.getLatitude(), l.getLongitude(),
                    l.getBusinessName() != null ? l.getBusinessName() : l.getArea(), "trade in");
            text = OFFER + ": " + buyer.getName() + " offers their " + o.exTitle() + km(o.getExKm()) + " in exchange for your " + l.title()
                    + (o.getExExpected() != null ? " - they hope to get " + MarketService.rupees(o.getExExpected()) + " for it" : "") + ".";
        } else {
            throw ApiException.badRequest("A price offer or an exchange?");
        }
        o = offers.save(o);
        alerts.raiseAboutMarket(l.getSeller(), RiskLevel.MEDIUM, text, null, null, l.getId(), null);
        changed(o);
        return view(o, buyer);
    }

    /** The buyer's vehicle: their own listing of it, or what they typed. */
    private void exchangeVehicle(MarketOffer o, User buyer, OfferRequest r) {
        if (r.getExListingId() != null) {
            MarketListing x = listings.findById(r.getExListingId()).orElse(null);
            if (x == null || !x.getSeller().getId().equals(buyer.getId()) || !x.open()) throw ApiException.notFound("Your listing wasn't found.");
            o.setExListingId(x.getId());
            o.setExVehicleType(x.getVehicleType());
            o.setExMake(x.getMake());
            o.setExModel(x.getModel() + (x.getVariant() != null ? " " + x.getVariant() : ""));
            o.setExYear(x.getYear());
            o.setExKm(x.getKmDriven());
            o.setExFuel(x.getFuel());
            o.setExOwners(x.getOwnerCount());
            o.setExPlate(x.getPlate());
            o.setExPlateKey(x.getPlateKey());
            o.setExExpected(r.getExExpected() != null ? r.getExExpected() : x.getPrice());
            if (o.getExPlateKey() == null) throw ApiException.badRequest("That listing has no number plate.");
            return;
        }
        String type = r.getExVehicleType() == null ? null : r.getExVehicleType().trim().toUpperCase(Locale.ROOT);
        if (type == null || !MarketService.VEHICLE_TYPES.contains(type)) throw ApiException.badRequest("What kind of vehicle is yours?");
        if (text(r.getExMake()) == null || text(r.getExModel()) == null) throw ApiException.badRequest("Type your vehicle's make and model.");
        if (r.getExYear() == null) throw ApiException.badRequest("Which year is your vehicle from?");
        String key = CarGuardService.plateKey(r.getExPlate());
        if (key == null || key.length() < 6) throw ApiException.badRequest("Type your vehicle's number plate - it's checked against stolen vehicles.");
        o.setExVehicleType(type);
        o.setExMake(r.getExMake().trim());
        o.setExModel(r.getExModel().trim());
        o.setExYear(r.getExYear());
        o.setExKm(r.getExKm());
        o.setExFuel(r.getExFuel() != null && MarketService.FUELS.contains(r.getExFuel().toUpperCase(Locale.ROOT)) ? r.getExFuel().toUpperCase(Locale.ROOT) : null);
        o.setExOwners(r.getExOwners());
        o.setExPlate(r.getExPlate().trim().toUpperCase(Locale.ROOT));
        o.setExPlateKey(key);
        o.setExExpected(r.getExExpected());
    }

    /** The seller: accept (an exchange needs what it's worth: value), counter (value), or decline. */
    @Transactional
    public Map<String, Object> answer(User seller, Long id, String action, OfferRequest r) {
        MarketOffer o = offers.findById(id).orElseThrow(() -> ApiException.notFound("Offer not found"));
        MarketListing l = o.getListing();
        if (!l.getSeller().getId().equals(seller.getId())) throw ApiException.notFound("Offer not found");
        Long value = r != null ? r.getValue() : null;
        String note = r != null ? text(r.getNote()) : null;
        String to;
        switch (action) {
            case "accept" -> {
                if (!o.open()) throw ApiException.badRequest("It's " + o.getStatus().toLowerCase(Locale.ROOT) + ".");
                if (!l.open()) throw ApiException.badRequest("It isn't for sale any more.");
                if (o.exchange()) {
                    if (value == null) throw ApiException.badRequest("What's their " + o.getExModel() + " worth to you?");
                    if (value >= l.getPrice()) throw ApiException.badRequest("That's more than your vehicle's price.");
                    o.setSellerValue(value);
                } else {
                    o.setSellerValue(o.getPrice());
                }
                o.setStatus("ACCEPTED");
                reserve(l);
                to = OFFER + ": " + name(l) + " accepted - " + deal(o) + ". It's reserved for you; call them to fix the day: " + seller.getPhone() + ".";
            }
            case "counter" -> {
                if (!o.open()) throw ApiException.badRequest("It's " + o.getStatus().toLowerCase(Locale.ROOT) + ".");
                if (value == null) throw ApiException.badRequest(o.exchange() ? "What's their vehicle worth to you?" : "What price would you take?");
                if (!o.exchange() && (value <= o.getPrice() || value > l.getPrice())) {
                    throw ApiException.badRequest("Counter between their offer (" + MarketService.rupees(o.getPrice()) + ") and your price.");
                }
                if (o.exchange() && value >= l.getPrice()) throw ApiException.badRequest("That's more than your vehicle's price.");
                o.setSellerValue(value);
                o.setStatus("COUNTERED");
                to = OFFER + ": " + name(l) + (o.exchange()
                        ? " can give " + MarketService.rupees(value) + " for your " + o.exTitle() + " - you'd pay " + MarketService.rupees(l.getPrice() - value) + " for the " + l.title()
                        : " would take " + MarketService.rupees(value) + " for the " + l.title() + " (you offered " + MarketService.rupees(o.getPrice()) + ")") + ". Open it to say yes.";
            }
            case "decline" -> {
                if (!o.open() && !"ACCEPTED".equals(o.getStatus())) throw ApiException.badRequest("It's " + o.getStatus().toLowerCase(Locale.ROOT) + ".");
                boolean wasAccepted = "ACCEPTED".equals(o.getStatus());
                o.setStatus("DECLINED");
                if (wasAccepted) unreserve(l, o);
                to = OFFER + ": " + name(l) + (wasAccepted ? " called off the deal for the " : " said no to your offer for the ") + l.title() + ".";
            }
            default -> throw ApiException.notFound("Unknown action");
        }
        o.setSellerNote(note);
        o.setAnsweredAt(MarketService.now());
        offers.save(o);
        alerts.raiseAboutMarket(o.getBuyer(), RiskLevel.MEDIUM, to + (note != null ? " \"" + note + "\"" : ""), null, null, l.getId(), null);
        changed(o);
        return view(o, seller);
    }

    /** The buyer: agree to the seller's counter, or withdraw. */
    @Transactional
    public Map<String, Object> buyerAct(User buyer, Long id, String action) {
        MarketOffer o = offers.findById(id).orElseThrow(() -> ApiException.notFound("Offer not found"));
        if (!o.getBuyer().getId().equals(buyer.getId())) throw ApiException.notFound("Offer not found");
        MarketListing l = o.getListing();
        String to;
        if (action.equals("agree")) {
            if (!"COUNTERED".equals(o.getStatus())) throw ApiException.badRequest("There's nothing to agree to.");
            if (!l.open()) throw ApiException.badRequest("It isn't for sale any more.");
            o.setStatus("ACCEPTED");
            reserve(l);
            to = OFFER + ": " + buyer.getName() + " agreed - " + deal(o) + ". It's reserved for them: " + buyer.getPhone() + ".";
        } else if (action.equals("withdraw")) {
            if (!o.open() && !"ACCEPTED".equals(o.getStatus())) throw ApiException.badRequest("It's " + o.getStatus().toLowerCase(Locale.ROOT) + ".");
            boolean wasAccepted = "ACCEPTED".equals(o.getStatus());
            o.setStatus("WITHDRAWN");
            if (wasAccepted) unreserve(l, o);
            to = OFFER + ": " + buyer.getName() + " withdrew their offer for your " + l.title() + (wasAccepted ? " - it's back on sale." : ".");
        } else {
            throw ApiException.notFound("Unknown action");
        }
        offers.save(o);
        alerts.raiseAboutMarket(l.getSeller(), RiskLevel.MEDIUM, to, null, null, l.getId(), null);
        changed(o);
        return view(o, buyer);
    }

    /**
     * After an exchange sale, a dealer or showroom puts the vehicle they took
     * in up for sale: its details filled in, at their price, where they are.
     */
    public Map<String, Object> toStock(User seller, Long id, OfferRequest r) {
        MarketOffer o = offers.findById(id).orElseThrow(() -> ApiException.notFound("Offer not found"));
        MarketListing l = o.getListing();
        if (!l.getSeller().getId().equals(seller.getId())) throw ApiException.notFound("Offer not found");
        if (!o.exchange() || !"DONE".equals(o.getStatus())) throw ApiException.badRequest("That's only for a vehicle you took in exchange.");
        if (o.getStockListingId() != null) throw ApiException.badRequest("It's in your stock already.");
        ListingRequest q = new ListingRequest();
        q.setSellerType(l.getSellerType());
        q.setBusinessName(l.getBusinessName());
        q.setVehicleType(o.getExVehicleType());
        q.setCondition("USED");
        q.setMake(o.getExMake());
        q.setModel(o.getExModel());
        q.setYear(o.getExYear());
        q.setKmDriven(o.getExKm());
        q.setFuel(o.getExFuel());
        q.setOwnerCount(o.getExOwners() != null ? Math.min(10, o.getExOwners() + 1) : null);
        q.setPlate(o.getExPlate());
        long price = r != null && r.getValue() != null ? r.getValue()
                : o.getSellerValue() != null ? Math.round(o.getSellerValue() * 1.12 / 1000.0) * 1000 : 0;
        q.setPrice(price);
        q.setLatitude(l.getLatitude());
        q.setLongitude(l.getLongitude());
        q.setArea(l.getArea());
        q.setExchange(l.getExchange());
        q.setDescription("Taken in exchange at " + (l.getBusinessName() != null ? l.getBusinessName() : "our showroom") + ".");
        Map<String, Object> made = market.create(seller, q);
        o.setStockListingId(((Number) made.get("id")).longValue());
        offers.save(o);
        return made;
    }

    // ------------------------------------------------------------------ helpers

    /** Accepted: no new test drives from others (the buyer with the deal can still book one). */
    private void reserve(MarketListing l) {
        if ("ACTIVE".equals(l.getStatus())) {
            l.setStatus("RESERVED");
            listings.save(l);
        }
    }

    /** The deal fell through: back on sale, unless another accepted offer holds it. */
    private void unreserve(MarketListing l, MarketOffer gone) {
        boolean other = offers.findByListingIdOrderByCreatedAtDesc(l.getId()).stream()
                .anyMatch(x -> !x.getId().equals(gone.getId()) && "ACCEPTED".equals(x.getStatus()));
        if (!other && "RESERVED".equals(l.getStatus())) {
            l.setStatus("ACTIVE");
            listings.save(l);
        }
    }

    /** "₹4,80,000 for the Swift" / "your Activa at ₹55,000 - you pay ₹18,20,000 for the Innova". */
    static String deal(MarketOffer o) {
        MarketListing l = o.getListing();
        if (o.exchange()) {
            return "your " + o.exTitle() + " at " + MarketService.rupees(o.getSellerValue()) + " - you pay "
                    + MarketService.rupees(l.getPrice() - o.getSellerValue()) + " for the " + l.title();
        }
        return MarketService.rupees(o.getSellerValue()) + " for the " + l.title();
    }

    private static String name(MarketListing l) {
        return l.getBusinessName() != null ? l.getBusinessName() : l.getSeller().getName();
    }

    private static String km(Integer km) {
        return km != null ? String.format(Locale.ROOT, " (%,d km)", km) : "";
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void changed(MarketOffer o) {
        Map<String, Object> m = new HashMap<>();
        m.put("listingId", o.getListing().getId());
        m.put("offerId", o.getId());
        m.put("sellerId", o.getListing().getSeller().getId());
        m.put("buyerId", o.getBuyer().getId());
        webSocket.broadcast("MARKET", m);
    }

    /** An offer as the buyer or the seller sees it (the seller sees the full plate of the vehicle offered once they have a deal; it is checked against stolen ones either way). */
    public static Map<String, Object> view(MarketOffer o, User viewer) {
        MarketListing l = o.getListing();
        boolean seller = l.getSeller().getId().equals(viewer.getId());
        boolean dealDone = "ACCEPTED".equals(o.getStatus()) || "DONE".equals(o.getStatus());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("role", seller ? "SELLER" : "BUYER");
        m.put("kind", o.getKind());
        m.put("status", o.getStatus());
        m.put("listingId", l.getId());
        m.put("title", l.title());
        m.put("askingPrice", l.getPrice());
        m.put("price", o.getPrice());
        m.put("sellerValue", o.getSellerValue());
        m.put("note", o.getNote());
        m.put("sellerNote", o.getSellerNote());
        m.put("waitingFor", "OPEN".equals(o.getStatus()) ? "SELLER" : "COUNTERED".equals(o.getStatus()) ? "BUYER" : "");
        m.put("buyerName", o.getBuyer().getName());
        m.put("buyerPhone", seller && dealDone ? o.getBuyer().getPhone() : null);
        m.put("sellerName", name(l));
        m.put("sellerPhone", !seller && dealDone ? l.getSeller().getPhone() : null);
        m.put("youPay", o.getSellerValue() == null ? null : o.exchange() ? l.getPrice() - o.getSellerValue() : o.getSellerValue());
        if (o.exchange()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("title", o.exTitle());
            x.put("vehicleType", o.getExVehicleType());
            x.put("make", o.getExMake());
            x.put("model", o.getExModel());
            x.put("year", o.getExYear());
            x.put("kmDriven", o.getExKm());
            x.put("fuel", o.getExFuel());
            x.put("ownerCount", o.getExOwners());
            x.put("plate", !seller || dealDone ? o.getExPlate() : MarketService.maskPlate(o.getExPlateKey()));
            x.put("expected", o.getExExpected());
            x.put("listingId", o.getExListingId());
            x.put("plateChecked", true);
            m.put("exchange", x);
        } else {
            m.put("exchange", null);
        }
        m.put("stockListingId", o.getStockListingId());
        m.put("canAddToStock", seller && o.exchange() && "DONE".equals(o.getStatus()) && o.getStockListingId() == null
                && ("DEALER".equals(l.getSellerType()) || "SHOWROOM".equals(l.getSellerType())));
        m.put("createdAt", o.getCreatedAt());
        m.put("answeredAt", o.getAnsweredAt());
        return m;
    }
}
