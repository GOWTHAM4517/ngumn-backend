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
