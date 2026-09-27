package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String vehicleCode;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VehicleType vehicleType;

    /**
     * How the owner is travelling right now (walking, bike, car...), set
     * from the app. Null on rows from before travel modes - see
     * effectiveTravelMode(). A plain varchar (not a database enum) so new
     * modes never need a schema change.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, columnDefinition = "varchar(20)")
    private TravelMode travelMode;

    /** Number plate the owner chose to show (optional), e.g. "AP 16 BX 2231". */
    @Column(length = 20)
    private String plateNumber;

    private Double currentLatitude;

    private Double currentLongitude;

    @Builder.Default
    private Double speedKmh = 0.0;

    @Builder.Default
    private Double directionDegrees = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VehicleStatus status = VehicleStatus.ACTIVE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean emergencyStatus = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSimulated = false;

    private LocalDateTime lastLocationUpdate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * The travel mode to go by: the one the owner chose, or - for rows
     * saved before travel modes existed - a guess from the vehicle type
     * (and walking for people who signed up as pedestrians).
     */
    public TravelMode effectiveTravelMode() {
        if (travelMode != null) return travelMode;
        if (owner != null && owner.getRole() == Role.PEDESTRIAN && vehicleType != VehicleType.EMERGENCY) {
            return TravelMode.WALK;
        }
        return TravelMode.from(vehicleType);
    }

    /** True if the position was updated within the last `seconds` seconds. */
    public boolean seenWithin(LocalDateTime now, long seconds) {
        return currentLatitude != null && currentLongitude != null && lastLocationUpdate != null
                && !lastLocationUpdate.isBefore(now.minusSeconds(seconds));
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = VehicleStatus.ACTIVE;
        if (this.emergencyStatus == null) this.emergencyStatus = false;
        if (this.isSimulated == null) this.isSimulated = false;
        if (this.speedKmh == null) this.speedKmh = 0.0;
        if (this.directionDegrees == null) this.directionDegrees = 0.0;
    }
}
