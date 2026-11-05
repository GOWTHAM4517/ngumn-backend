package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A customer's test drive of a showroom car: who, until when, how far from
 * the showroom - and, from the tracker, how it went (distance, top speed,
 * times over the limit, left the area, back late).
 */
@Entity
@Table(name = "test_drives", indexes = {
        @Index(name = "idx_testdrive_car", columnList = "car_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestDrive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @ManyToOne
    @JoinColumn(name = "started_by", nullable = false)
    private User startedBy;

    @Column(nullable = false, length = 80)
    private String customerName;

    @Column(length = 20)
    private String customerPhone;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime dueBackAt;

    private Double centerLatitude;
    private Double centerLongitude;

    @Column(nullable = false)
    private Integer radiusM;

    private LocalDateTime endedAt;

    /** ACTIVE or ENDED. */
    @Column(nullable = false, length = 10)
    private String status;

    @Builder.Default
    private Double distanceM = 0.0;

    @Builder.Default
    private Double maxSpeedKmh = 0.0;

    /** Times it went over the car's limit (each time counted once). */
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

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
