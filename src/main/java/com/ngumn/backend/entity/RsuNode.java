package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Simulated Roadside Unit (RSU). For this prototype an RSU is a
 * software node (backend concept) that can be "pinged" to represent a
 * roadside sensor/beacon reporting into NGUMN. No physical government
 * RSU hardware is implied or deployed.
 */
@Entity
@Table(name = "rsu_nodes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RsuNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String rsuCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    @Builder.Default
    private Boolean online = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSimulated = true;

    private LocalDateTime lastPing;
}
