package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Putting a vehicle up for sale, or changing it (PATCH: only what's sent
 * changes; clearCar unlinks the Car guard car).
 */
@Data
public class ListingRequest {
    /** OWNER, INDIVIDUAL, DEALER, SHOWROOM. */
    @Size(max = 12)
    private String sellerType;

    @Size(max = 80)
    private String businessName;

    /** CAR, BIKE, SCOOTER, AUTO, COMMERCIAL. */
    @Size(max = 12)
    private String vehicleType;

    /** USED or NEW. */
    @Size(max = 6)
    private String condition;

    @Size(max = 40)
    private String make;

    @Size(max = 60)
    private String model;

    @Size(max = 60)
    private String variant;

    @Min(1980)
    @Max(2100)
    private Integer year;

    @Size(max = 10)
    private String fuel;

    @Size(max = 10)
    private String transmission;

    @Min(0)
    @Max(2_000_000)
    private Integer kmDriven;

    @Min(1)
    @Max(10)
    private Integer ownerCount;

    @Size(max = 30)
    private String colour;

    @Size(max = 20)
    private String plate;

    @Min(1000)
    @Max(500_000_000)
    private Long price;

    private Boolean negotiable;

    @Size(max = 1000)
    private String description;

    private Boolean rcInHand;
    private Boolean insuranceValid;
    private Boolean serviceRecords;
    private Boolean loanCleared;

    private Double latitude;
    private Double longitude;

    @Size(max = 80)
    private String area;

    private Long carId;
    private Boolean clearCar;

    private Boolean testDrives;

    @Min(10)
    @Max(120)
    private Integer driveMinutes;

    @Min(1000)
    @Max(25000)
    private Integer driveRadiusM;

    @Min(30)
    @Max(150)
    private Integer speedLimitKmh;
}
