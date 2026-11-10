package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An offer on a vehicle for sale (MarketOfferService): a price (PRICE), or
 * the buyer's own vehicle in exchange (EXCHANGE) - how showrooms and dealers
 * usually sell. The seller accepts, counters (a price, or what the exchanged
 * vehicle is worth to them) or declines.
 *
 * <pre>
 *   OPEN -> COUNTERED -> ACCEPTED -> DONE (sold to this buyer)
 *     \-> DECLINED, WITHDRAWN (the buyer), CLOSED (sold to someone else / taken off)
 * </pre>
 */
@Entity
@Table(name = "market_offers", indexes = {
        @Index(name = "idx_offer_listing", columnList = "listing_id"),
        @Index(name = "idx_offer_buyer", columnList = "buyer_id"),
        @Index(name = "idx_offer_explate", columnList = "ex_plate_key")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "listing_id", nullable = false)
    private MarketListing listing;

    @ManyToOne
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    /** PRICE or EXCHANGE. */
    @Column(nullable = false, length = 8)
    private String kind;

    /** PRICE: what the buyer offers to pay. */
    private Long price;

    // ------------------------------------------------ EXCHANGE: the buyer's vehicle

    @Column(name = "ex_vehicle_type", length = 12)
    private String exVehicleType;
    @Column(name = "ex_make", length = 40)
    private String exMake;
    @Column(name = "ex_model", length = 60)
    private String exModel;
    @Column(name = "ex_year")
    private Integer exYear;
    @Column(name = "ex_km")
    private Integer exKm;
    @Column(name = "ex_fuel", length = 10)
    private String exFuel;
    @Column(name = "ex_owners")
    private Integer exOwners;
    @Column(name = "ex_plate", length = 20)
    private String exPlate;
    @Column(name = "ex_plate_key", length = 20)
    private String exPlateKey;
    /** What the buyer hopes to get for it. */
    @Column(name = "ex_expected")
    private Long exExpected;
    /** The buyer's own listing of it on Raksio, if they had one (taken off when the exchange is done). */
    @Column(name = "ex_listing_id")
    private Long exListingId;

    @Column(length = 300)
    private String note;

    /** OPEN, COUNTERED, ACCEPTED, DECLINED, WITHDRAWN, CLOSED, DONE. */
    @Column(nullable = false, length = 10)
    private String status;

    /** The seller's answer: a counter price (PRICE) or what the buyer's vehicle is worth to them (EXCHANGE). */
    private Long sellerValue;

    @Column(length = 300)
    private String sellerNote;

    /** The listing the exchanged vehicle became in the seller's stock. */
    private Long stockListingId;

    private LocalDateTime answeredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean open() {
        return "OPEN".equals(status) || "COUNTERED".equals(status);
    }

    public boolean exchange() {
        return "EXCHANGE".equals(kind);
    }

    /** "2022 Honda Activa 6G". */
    public String exTitle() {
        if (!exchange()) return null;
        StringBuilder s = new StringBuilder();
        if (exYear != null) s.append(exYear).append(' ');
        s.append(exMake != null ? exMake.trim() : "").append(' ').append(exModel != null ? exModel.trim() : "");
        return s.toString().trim();
    }
}
