package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A car watched by Car guard (CarGuardService) - someone's own car, or one
 * of a showroom's used cars (put up for sale in Buy & Sell, MarketService). A small GPS tracker in the car (not a phone)
 * sends its position to /api/tracker with this car's secret trackerId; with
 * no tracker the car can still be reported stolen, which tells the police
 * and Raksio users near where it was last seen.
 */
@Entity
@Table(name = "guarded_cars", indexes = {
        @Index(name = "idx_car_owner", columnList = "owner_id"),
        @Index(name = "idx_car_tracker", columnList = "tracker_id", unique = true),
        @Index(name = "idx_car_token", columnList = "track_token", unique = true),
        @Index(name = "idx_car_plate", columnList = "plate_key")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardedCar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** "My Innova", "U-Trust stock 12". */
    @Column(nullable = false, length = 60)
    private String name;

    /** As typed: "AP 16 BX 2231". */
    @Column(length = 20)
    private String plate;

    /** For matching: "AP16BX2231". */
    @Column(name = "plate_key", length = 20)
    private String plateKey;

    @Column(length = 60)
    private String model;

    @Column(length = 30)
    private String colour;

    /** The tracker's device id (secret - it's what lets a device send positions). */
    @Column(name = "tracker_id", nullable = false, length = 40)
    private String trackerId;

    /** Secret part of /car.html?t= - the police's live page while it's stolen. */
    @Column(name = "track_token", nullable = false, length = 40)
    private String trackToken;

    /** Home / showroom / yard: centre and radius. */
    private Double homeLatitude;
    private Double homeLongitude;
    private Integer homeRadiusM;

    // (guard_on, night_guard, night_from, night_to: the old parked-car guard, taken out -
    // the columns stay in the database, unused.)

    /** Tell the owner when it goes over speedLimitKmh (a learner, a driver). */
    @Builder.Default
    private Boolean speedAlerts = false;

    @Builder.Default
    private Integer speedLimitKmh = 80;

    /** NORMAL or STOLEN. */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "NORMAL";

    private LocalDateTime stolenAt;
    private LocalDateTime recoveredAt;
    /** The police help request for the theft. */
    private Long theftIncidentId;

    // Latest from the tracker
    private Double lastLatitude;
    private Double lastLongitude;
    private Double lastSpeedKmh;
    private Double lastHeading;
    private Boolean lastIgnition;
    private Integer lastBattery;
    private LocalDateTime lastSeenAt;

    /** Where it was standing still since when (the parked spot is set after 2 minutes still). */
    private Double stillLatitude;
    private Double stillLongitude;
    private LocalDateTime stillSince;
    private Double parkedLatitude;
    private Double parkedLongitude;

    // So the same thing isn't said over and over
    private LocalDateTime lastSpeedAlertAt;
    private LocalDateTime lastBroadcastAt;
    @Builder.Default
    private Boolean silentAlerted = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "NORMAL";
    }

    public boolean stolen() {
        return "STOLEN".equals(status);
    }

    /** "White Toyota Innova, AP 16 BX 2231". */
    public String describe() {
        StringBuilder s = new StringBuilder();
        if (colour != null && !colour.isBlank()) s.append(colour.trim()).append(' ');
        s.append(model != null && !model.isBlank() ? model.trim() : name);
        if (plate != null && !plate.isBlank()) s.append(", ").append(plate.trim());
        return s.toString();
    }
}
