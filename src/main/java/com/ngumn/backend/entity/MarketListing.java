package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A vehicle for sale in Buy &amp; Sell (MarketService): by its owner, by
 * someone selling it for family or a friend, by a used-vehicle dealer
 * (an outside seller) or by a showroom. Buyers book test drives on it
 * (TestDriveBooking). Its number plate is checked against cars reported
 * stolen in Raksio when it's listed and every time it's shown.
 */
@Entity
@Table(name = "market_listings", indexes = {
        @Index(name = "idx_listing_seller", columnList = "seller_id"),
        @Index(name = "idx_listing_status", columnList = "status"),
        @Index(name = "idx_listing_plate", columnList = "plate_key"),
        @Index(name = "idx_listing_car", columnList = "car_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    /** OWNER (my own), INDIVIDUAL (for family / a friend), DEALER (used-vehicle dealer, outside seller), SHOWROOM. */
    @Column(name = "seller_type", nullable = false, length = 12)
    private String sellerType;

    /** The dealer's or showroom's name ("U-Trust, Lakshmi Toyota"). */
    @Column(length = 80)
    private String businessName;

    /** CAR, BIKE, SCOOTER, AUTO, COMMERCIAL. */
    @Column(name = "vehicle_type", nullable = false, length = 12)
    private String vehicleType;

    /** USED or NEW. ("condition" is a reserved word in MySQL.) */
    @Column(name = "vehicle_condition", nullable = false, length = 6)
    private String condition;

    @Column(nullable = false, length = 40)
    private String make;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(length = 60)
    private String variant;

    @Column(name = "model_year")
    private Integer year;

    /** PETROL, DIESEL, CNG, ELECTRIC, HYBRID, LPG. */
    @Column(length = 10)
    private String fuel;

    /** MANUAL or AUTOMATIC. */
    @Column(length = 10)
    private String transmission;

    private Integer kmDriven;

    /** 1 = first owner. */
    private Integer ownerCount;

    @Column(length = 30)
    private String colour;

    /** As typed: "AP 16 BX 2231". Shown in full only to the seller and to buyers with an accepted test drive. */
    @Column(length = 20)
    private String plate;

    /** For matching: "AP16BX2231". */
    @Column(name = "plate_key", length = 20)
    private String plateKey;

    /** Rupees. */
    @Column(nullable = false)
    private Long price;

    @Builder.Default
    private Boolean negotiable = true;

    @Column(length = 1000)
    private String description;

    /** Papers: RC with the seller, insurance valid, service records, no loan on it (NOC). */
    @Builder.Default
    private Boolean rcInHand = false;
    @Builder.Default
    private Boolean insuranceValid = false;
    @Builder.Default
    private Boolean serviceRecords = false;
    @Builder.Default
    private Boolean loanCleared = false;

    /** Where it can be seen (the showroom, the seller's area). */
    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(length = 80)
    private String area;

    /** The Car guard car it is (with a GPS tracker, test drives are watched by the tracker). */
    @Column(name = "car_id")
    private Long carId;

    /** Test drives: allowed, how long, how far from the start, speed limit. */
    @Builder.Default
    private Boolean testDrives = true;
    @Builder.Default
    private Integer driveMinutes = 30;
    @Builder.Default
    private Integer driveRadiusM = 5000;
    @Builder.Default
    private Integer speedLimitKmh = 80;

    /** Buyers may offer their own vehicle in exchange (MarketOfferService) - usual for dealers and showrooms. */
    @Column(name = "takes_exchange")
    @Builder.Default
    private Boolean exchange = false;

    /**
     * While the seller drives it (their Raksio number plate is this one) or its
     * Car guard tracker says it's moving, people looking for one like it who
     * pass close by are told it's for sale (MarketRoadService).
     */
    @Builder.Default
    private Boolean showOnRoad = true;

    /** Where it was last seen moving on the road, and when (private: only used for "next to you"). */
    private Double roadLatitude;
    private Double roadLongitude;
    private Double roadSpeedKmh;
    private LocalDateTime roadAt;

    /** Up to 6 photo tokens, comma-separated, in order (the photos are in listing_photos). */
    @Column(length = 200)
    private String photoTokens;

    /** ACTIVE, RESERVED (token paid), SOLD, WITHDRAWN, HIDDEN (reported by several people - waiting for the admin). */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    private LocalDateTime soldAt;
    private Long soldPrice;

    @ManyToOne
    @JoinColumn(name = "sold_to_id")
    private User soldTo;

    @Builder.Default
    private Integer views = 0;

    @Builder.Default
    private Integer reportCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = "ACTIVE";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Shown to buyers (ACTIVE or RESERVED). */
    public boolean open() {
        return "ACTIVE".equals(status) || "RESERVED".equals(status);
    }

    /** "2019 Maruti Suzuki Swift VXi". */
    public String title() {
        StringBuilder s = new StringBuilder();
        if (year != null) s.append(year).append(' ');
        s.append(make.trim()).append(' ').append(model.trim());
        if (variant != null && !variant.isBlank()) s.append(' ').append(variant.trim());
        return s.toString();
    }
}
