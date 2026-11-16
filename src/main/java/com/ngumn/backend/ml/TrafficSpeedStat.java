package com.ngumn.backend.ml;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * What the traffic model has learned about one direction of one road at one
 * hour of the week (or all hours together, hourOfWeek = -1): how many speed
 * readings it has seen, their mean and spread (Welford's running variance).
 */
@Entity
@Table(name = "traffic_speed_stats", indexes = @Index(name = "idx_traffic_road", columnList = "road_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficSpeedStat {

    /** "roadId:direction:hourOfWeek". */
    @Id
    @Column(length = 48)
    private String id;

    @Column(name = "road_id")
    private Long roadId;

    /** 1 = the road's drawing direction, -1 = against it. */
    private Integer direction;

    /** 0 = Monday 00:00-01:00 ... 167 = Sunday 23:00-24:00; -1 = all hours. */
    private Integer hourOfWeek;

    private Long samples;
    private Double meanKmh;
    /** Sum of squared differences from the mean (Welford). */
    private Double m2;

    private LocalDateTime updatedAt;
}
