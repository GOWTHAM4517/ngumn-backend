package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A crash Raksio detected, or an SOS the person pressed (see SosService). */
@Data
public class SosRequest {
    /** CRASH, SOS, CHECKIN (a Safe Walk nobody ended in time) or SHARE (live location, not an emergency). */
    @NotBlank
    private String kind;

    /** SOS only: ACCIDENT, MEDICAL, FIRE or UNSAFE (default ACCIDENT). */
    private String reason;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    @Min(0)
    @Max(400)
    private Integer speedKmh;

    /** BIKE, CYCLE, AUTO, CAR, BUS, TRUCK, OTHER. */
    private String vehicleKind;

    @Size(max = 500)
    private String description;
}
