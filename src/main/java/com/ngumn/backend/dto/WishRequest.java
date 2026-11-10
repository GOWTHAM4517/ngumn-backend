package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** "Looking for": what to watch for (all optional), and how to be told. */
@Data
public class WishRequest {
    @Size(max = 12)
    private String vehicleType;
    @Size(max = 60)
    private String q;
    @Min(0)
    @Max(500_000_000)
    private Long minPrice;
    @Min(0)
    @Max(500_000_000)
    private Long maxPrice;
    @Min(0)
    @Max(2_000_000)
    private Integer maxKm;
    @Min(1980)
    @Max(2100)
    private Integer minYear;
    @Size(max = 10)
    private String fuel;
    @Size(max = 10)
    private String transmission;
    @Size(max = 10)
    private String sellerType;
    @Size(max = 6)
    private String condition;
    private Boolean onRoad;
    private Boolean newListings;
    private Boolean active;
}
