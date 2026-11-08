package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Adding a car to Car guard, or changing it (PATCH: only what's sent
 * changes). Home is the place it's normally parked - home, the showroom, the
 * stock yard; clearHome removes it.
 */
@Data
public class CarRequest {
    @Size(max = 60)
    private String name;

    @Size(max = 20)
    private String plate;

    @Size(max = 60)
    private String model;

    @Size(max = 30)
    private String colour;

    private Boolean speedAlerts;

    @Min(20)
    @Max(200)
    private Integer speedLimitKmh;

    private Double homeLatitude;
    private Double homeLongitude;

    @Min(50)
    @Max(5000)
    private Integer homeRadiusM;

    private Boolean clearHome;

    /**
     * The tracker's own id, when it has a fixed one (a GPS tracker's IMEI as
     * Traccar knows it). Left out, Car guard makes a secret one.
     */
    @Size(min = 6, max = 40)
    private String trackerId;
}
