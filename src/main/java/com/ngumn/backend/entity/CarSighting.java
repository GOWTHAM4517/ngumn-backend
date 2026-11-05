package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Someone saw a stolen car: a Raksio user tapped "I saw it" (source APP), or
 * a rule-breaker report named its number plate (REPORT).
 */
@Entity
@Table(name = "car_sightings", indexes = {
        @Index(name = "idx_sighting_car", columnList = "car_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarSighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @ManyToOne
    @JoinColumn(name = "reporter_id")
    private User reporter;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(length = 200)
    private String note;

    /** APP or REPORT. */
    @Column(nullable = false, length = 10)
    private String source;

    @Column(name = "seen_at", nullable = false)
    private LocalDateTime at;

    @PrePersist
    protected void onCreate() {
        if (at == null) at = LocalDateTime.now();
        if (source == null) source = "APP";
    }
}
