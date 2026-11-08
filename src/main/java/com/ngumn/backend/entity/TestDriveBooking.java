package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A test drive (MarketService), from the buyer's request to both sides'
 * ratings:
 *
 * <pre>
 *   REQUESTED -> ACCEPTED -> ACTIVE -> ENDED
 *       |  \-> DECLINED       (the seller types the buyer's meeting code to start)
 *       \-> (a new time from the seller, proposedAt, until the buyer agrees)
 *   CANCELLED (either side, before it starts), EXPIRED (never answered / never met)
 * </pre>
 *
 * A walk-in customer at a showroom or dealer (no Raksio account) is a
 * booking that starts ACTIVE straight away. During the drive the car's GPS
 * tracker (TRACKER) or the phones of the people in the car (PHONE) send its
 * position; time allowed, area and speed limit are watched.
 */
@Entity
@Table(name = "test_drive_bookings", indexes = {
        @Index(name = "idx_booking_listing", columnList = "listing_id"),
        @Index(name = "idx_booking_car", columnList = "car_id"),
        @Index(name = "idx_booking_buyer", columnList = "buyer_id"),
        @Index(name = "idx_booking_seller", columnList = "seller_id"),
        @Index(name = "idx_booking_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestDriveBooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The vehicle for sale (null for a walk-in drive of a Car guard car that isn't listed). */
    @ManyToOne
    @JoinColumn(name = "listing_id")
    private MarketListing listing;

    /** The Car guard car, when the vehicle has one (its tracker can watch the drive). */
    @Column(name = "car_id")
    private Long carId;

    @ManyToOne
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    /** The buyer's Raksio account (null for a walk-in customer). */
    @ManyToOne
    @JoinColumn(name = "buyer_id")
    private User buyer;

    @Column(nullable = false, length = 80)
    private String buyerName;

    @Column(length = 20)
    private String buyerPhone;

    @Builder.Default
    private Boolean walkIn = false;

    /** The buyer says they hold a licence for this kind of vehicle (otherwise they ride along). */
    @Builder.Default
    private Boolean driverLicence = false;

    @Column(length = 300)
    private String note;

    /** When they meet (asked by the buyer, or the seller's new time once the buyer agreed). */
    @Column(nullable = false)
    private LocalDateTime meetAt;

    /** A new time from the seller, waiting for the buyer. */
    private LocalDateTime proposedAt;

    /** REQUESTED, ACCEPTED, DECLINED, CANCELLED, EXPIRED, ACTIVE, ENDED. */
    @Column(nullable = false, length = 10)
    private String status;

    /** Why it was declined or cancelled. */
    @Column(length = 200)
    private String answer;

    /** BUYER or SELLER. */
    @Column(length = 6)
    private String cancelledBy;

    /** Shown on the buyer's phone when they meet; the seller types it to start. */
    @Column(length = 4)
    private String meetCode;

    @Builder.Default
    private Boolean reminded = false;

    // ------------------------------------------------ the drive

    /** TRACKER (the car's GPS tracker) or PHONE (the phones in the car). */
    @Column(length = 8)
    private String trackedBy;

    private LocalDateTime startedAt;
    private LocalDateTime dueBackAt;
    private LocalDateTime endedAt;

    private Integer minutesAllowed;
    private Integer radiusM;
    private Integer speedLimitKmh;

    private Double centerLatitude;
    private Double centerLongitude;

    @Builder.Default
    private Double distanceM = 0.0;
    @Builder.Default
    private Double maxSpeedKmh = 0.0;
    @Builder.Default
    private Integer overLimitCount = 0;
    @Builder.Default
    private Boolean overLimitNow = false;
    @Builder.Default
    private Boolean leftArea = false;
    @Builder.Default
    private Boolean lateAlerted = false;
    @Builder.Default
    private Boolean returnedLate = false;

    private Double lastLatitude;
    private Double lastLongitude;
    private Double lastSpeedKmh;
    private LocalDateTime lastAt;

    private Double stillLatitude;
    private Double stillLongitude;
    private LocalDateTime stillSince;

    // ------------------------------------------------ afterwards

    /** The buyer's rating of the seller and the vehicle (1-5), and whether it was as described. */
    private Integer buyerStars;
    private Boolean buyerAsDescribed;
    @Column(length = 300)
    private String buyerComment;

    /** The seller's rating of the buyer's driving (1-5). */
    private Integer sellerStars;
    @Column(length = 300)
    private String sellerComment;

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

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    /** Still to happen: asked, or agreed. */
    public boolean upcoming() {
        return "REQUESTED".equals(status) || "ACCEPTED".equals(status);
    }
}
