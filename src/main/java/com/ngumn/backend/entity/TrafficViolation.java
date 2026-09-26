package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One traffic-rule violation by a vehicle on the network, from one of
 * three sources (see ViolationSource): the server's rule monitor, Drive
 * Guard on the driver's phone, or a rule-breaker complaint that other
 * road users confirmed.
 *
 * This is a safety record in a prototype - not an e-challan, and it
 * carries no legal weight. "simulated" marks Test Drive and Demo Mode
 * records, which are kept apart from real ones.
 */
@Entity
@Table(name = "traffic_violations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    // Plain VARCHAR rather than a native MySQL ENUM column, so new
    // violation types can be added later without a manual ALTER TABLE.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30)")
    private ViolationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_source", nullable = false, length = 20, columnDefinition = "varchar(20)")
    @Builder.Default
    private ViolationSource source = ViolationSource.MONITOR;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private Double speedKmh;

    private Integer limitKmh;

    private Double headingDegrees;

    private Integer occupants;

    private Integer seatCapacity;

    @Column(length = 150)
    private String zoneName;

    /** Set when source = COMMUNITY: the complaint that was confirmed. */
    private Long complaintId;

    @Column(nullable = false, length = 300)
    private String message;

    @Column(nullable = false)
    @Builder.Default
    private Boolean simulated = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.simulated == null) this.simulated = false;
        if (this.source == null) this.source = ViolationSource.MONITOR;
    }
}
