package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prototype configurable speed-limit dataset. Real-world legal speed
 * limits are NOT derived from GPS - this is a simple point+radius demo
 * table an admin can seed so the risk engine has something concrete to
 * compare vehicle speed against.
 */
@Entity
@Table(name = "road_speed_limits")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadSpeedLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String roadName;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Integer speedLimitKmh;

    @Column(nullable = false)
    @Builder.Default
    private Double radiusMeters = 500.0;
}
