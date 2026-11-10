package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * "Looking for" (MarketRoadService): what a buyer wants - a kind of vehicle,
 * a budget, a make or model. Raksio tells them when one is put up for sale
 * (newListings) and when one that's for sale is right next to them on the
 * road, or they pass a dealer or showroom that has one (onRoad).
 */
@Entity
@Table(name = "market_wishes", indexes = {
        @Index(name = "idx_wish_user", columnList = "user_id"),
        @Index(name = "idx_wish_active", columnList = "active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketWish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** CAR, BIKE, SCOOTER, AUTO, COMMERCIAL - or any. */
    @Column(name = "vehicle_type", length = 12)
    private String vehicleType;

    /** Words that must all appear: "swift", "innova crysta", "activa". */
    @Column(length = 60)
    private String q;

    private Long minPrice;
    private Long maxPrice;
    private Integer maxKm;
    private Integer minYear;

    @Column(length = 10)
    private String fuel;

    @Column(length = 10)
    private String transmission;

    /** BUSINESS (dealers and showrooms), PRIVATE (owners), or any. */
    @Column(length = 10)
    private String sellerType;

    /** USED or NEW - or any. */
    @Column(name = "vehicle_condition", length = 6)
    private String condition;

    /** Tell me when one is next to me on the road, or I pass a dealer that has one. */
    @Builder.Default
    private Boolean onRoad = true;

    /** Tell me when one is put up for sale. */
    @Builder.Default
    private Boolean newListings = true;

    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** Does this vehicle for sale fit what they're looking for? */
    public boolean matches(MarketListing l) {
        if (vehicleType != null && !vehicleType.equals(l.getVehicleType())) return false;
        if (condition != null && !condition.equals(l.getCondition())) return false;
        if (minPrice != null && l.getPrice() < minPrice) return false;
        if (maxPrice != null && l.getPrice() > maxPrice) return false;
        if (maxKm != null && l.getKmDriven() != null && l.getKmDriven() > maxKm) return false;
        if (minYear != null && (l.getYear() == null || l.getYear() < minYear)) return false;
        if (fuel != null && !fuel.equals(l.getFuel())) return false;
        if (transmission != null && !transmission.equals(l.getTransmission())) return false;
        if (sellerType != null) {
            boolean business = "DEALER".equals(l.getSellerType()) || "SHOWROOM".equals(l.getSellerType());
            if (sellerType.equals("BUSINESS") != business) return false;
        }
        if (q != null && !q.isBlank()) {
            String hay = (l.title() + " " + (l.getColour() != null ? l.getColour() : "")).toLowerCase(Locale.ROOT);
            for (String w : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!hay.contains(w)) return false;
        }
        return true;
    }

    /** "car under ₹6 lakh", "diesel Swift, 2018 or newer", "bike, from a dealer or showroom". */
    public String describe() {
        StringBuilder s = new StringBuilder();
        String kind = vehicleType == null ? "vehicle" : switch (vehicleType) {
            case "CAR" -> "car";
            case "BIKE" -> "bike";
            case "SCOOTER" -> "scooter";
            case "AUTO" -> "auto";
            default -> "commercial vehicle";
        };
        if ("AUTOMATIC".equals(transmission)) s.append("automatic ");
        if (fuel != null) s.append(fuel.equals("CNG") || fuel.equals("LPG") ? fuel : fuel.toLowerCase(Locale.ROOT)).append(' ');
        if (q != null && !q.isBlank()) s.append(q.trim());
        else s.append(kind);
        if (minPrice != null && maxPrice != null) s.append(" for ").append(lakh(minPrice)).append(" to ").append(lakh(maxPrice));
        else if (maxPrice != null) s.append(" under ").append(lakh(maxPrice));
        else if (minPrice != null) s.append(" over ").append(lakh(minPrice));
        if (minYear != null) s.append(", ").append(minYear).append(" or newer");
        if (maxKm != null) s.append(String.format(Locale.ROOT, ", under %,d km", maxKm));
        if ("BUSINESS".equals(sellerType)) s.append(", from a dealer or showroom");
        if ("PRIVATE".equals(sellerType)) s.append(", from an owner");
        if ("NEW".equals(condition)) s.append(", new");
        return s.toString();
    }

    public static String lakh(long rupees) {
        if (rupees >= 100_000) {
            double l = rupees / 100_000.0;
            String t = l == Math.floor(l) ? String.valueOf((long) l) : String.format(Locale.ROOT, "%.1f", l);
            return "₹" + t + " lakh";
        }
        return "₹" + String.format(Locale.ROOT, "%,d", rupees);
    }
}
