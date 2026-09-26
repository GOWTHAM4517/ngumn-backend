package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Admin request to add a rule zone.
 *
 * kind = SPEED_LIMIT: centre (latitude, longitude), radiusMeters, speedLimitKmh.
 * kind = ONE_WAY:     start (latitude, longitude) -> end (endLatitude,
 *                     endLongitude) is the only permitted direction;
 *                     radiusMeters is the corridor half-width.
 * kind = NO_ENTRY:    centre (latitude, longitude) and radiusMeters.
 * vehicleTypes (ONE_WAY / NO_ENTRY only) limits the rule to those
 * vehicle types; leave empty for all vehicles.
 */
@Data
public class RuleZoneRequest {

    @NotBlank
    private String kind;

    @Size(max = 150)
    private String name;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Double endLatitude;

    private Double endLongitude;

    private Double radiusMeters;

    private Integer speedLimitKmh;

    private List<String> vehicleTypes;
}
