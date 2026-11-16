package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A speed zone set by the admin / traffic police for a real place (a school
 * zone, road works): a centre, a radius and a limit. It overrides the road's
 * own limit (RoadDataService) inside the circle.
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
