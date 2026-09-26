package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A traffic rule tied to a place, checked by Drive Guard in the app:
 *
 * - ONE_WAY:  the road segment from (latitude, longitude) to
 *             (endLatitude, endLongitude). Traffic may only travel from
 *             the start towards the end. radiusMeters is the half-width of
 *             the corridor around the segment that counts as "on this road".
 * - NO_ENTRY: the circle (latitude, longitude, radiusMeters) that
 *             vehicles must not drive into.
 *
 * vehicleTypes optionally limits the rule to some vehicle types, as
 * comma-separated VehicleType names (e.g. "TRUCK,BUS" for a heavy-vehicle
 * ban). Empty means the rule applies to every vehicle.
 *
 * Speed limits stay in RoadSpeedLimit (the risk engine uses that table
 * too). Like it, this is prototype data an admin adds for demos - it is
 * not an official road database.
 */
@Entity
@Table(name = "traffic_rule_zones")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficRuleZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    // Plain VARCHAR rather than a native MySQL ENUM column, so new zone
    // types can be added later without a manual ALTER TABLE.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private RuleZoneType zoneType;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private Double endLatitude;

    private Double endLongitude;

    @Column(nullable = false)
    @Builder.Default
    private Double radiusMeters = 30.0;

    @Column(length = 120)
    private String vehicleTypes;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.active == null) this.active = true;
        if (this.radiusMeters == null) this.radiusMeters = 30.0;
    }
}
