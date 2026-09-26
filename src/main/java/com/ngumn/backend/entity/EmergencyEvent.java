package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a "Green Corridor" priority-route simulation triggered by an
 * emergency vehicle. This is a SOFTWARE SIMULATION only - it does not
 * control any real traffic signal and does not integrate with any real
 * emergency-service or government system.
 */
@Entity
@Table(name = "emergency_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EmergencyStatus status = EmergencyStatus.ACTIVE;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    @PrePersist
    protected void onCreate() {
        if (this.startedAt == null) this.startedAt = LocalDateTime.now();
        if (this.status == null) this.status = EmergencyStatus.ACTIVE;
    }
}
