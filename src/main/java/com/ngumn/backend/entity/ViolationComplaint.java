package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A road user's report that someone broke a traffic rule (wrong-side
 * driving, jumping a signal, no helmet...).
 *
 * There is no admin in the loop: people nearby are asked "is this true?"
 * and vote. Enough "true" votes verify it - the reporter earns points and,
 * if the vehicle is on NGUMN, its driver is alerted and the violation is
 * added to their driving record. Enough "false" votes reject it and lower
 * the reporter's trust score. See CommunityService for the thresholds.
 *
 * The vehicle can be an NGUMN vehicle picked in the app
 * (accusedVehicle), a typed number plate, or unknown.
 */
@Entity
@Table(name = "violation_complaints")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ViolationComplaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, columnDefinition = "varchar(30)")
    private ViolationType type;

    @ManyToOne
    @JoinColumn(name = "accused_vehicle_id")
    private Vehicle accusedVehicle;

    @Column(length = 20)
    private String plateNumber;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer confirmations = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer denials = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = ReportStatus.PENDING;
        if (this.confirmations == null) this.confirmations = 0;
        if (this.denials == null) this.denials = 0;
    }
}
