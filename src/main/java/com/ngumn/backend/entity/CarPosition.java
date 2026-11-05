package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One position from a car's tracker - the trail (kept 30 days, see CarGuardService.cleanUp). */
@Entity
@Table(name = "car_positions", indexes = {
        @Index(name = "idx_carpos_car_at", columnList = "car_id, fix_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private Double speedKmh;
    private Double heading;
    private Boolean ignition;

    /** When the tracker took it ("at" itself is a keyword in some SQL dialects). */
    @Column(name = "fix_at", nullable = false)
    private LocalDateTime at;
}
