package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A buyer asks for a test drive (meetAt, note, driverLicence) - or a seller
 * starts one for a walk-in customer (name, phone, minutes, radiusM).
 */
@Data
public class BookingRequest {
    /** India time, e.g. "2026-11-09T10:30:00". */
    private LocalDateTime meetAt;

    @Size(max = 300)
    private String note;

    private Boolean driverLicence;

    @Size(max = 80)
    private String name;

    @Size(max = 20)
    private String phone;

    @Min(10)
    @Max(120)
    private Integer minutes;

    @Min(1000)
    @Max(25000)
    private Integer radiusM;

    /** Where the drive starts (the seller's phone), when no tracker gives it. */
    private Double latitude;
    private Double longitude;
}
