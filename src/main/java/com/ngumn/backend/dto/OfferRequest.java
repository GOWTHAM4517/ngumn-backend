package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A buyer's offer: a price (kind PRICE, price), or their vehicle in exchange
 * (kind EXCHANGE: its details, or exListingId - their own listing of it).
 * The seller's answer uses value (counter price / exchange value) and note.
 */
@Data
public class OfferRequest {
    @Size(max = 8)
    private String kind;
    @Min(1000)
    @Max(500_000_000)
    private Long price;
    @Size(max = 12)
    private String exVehicleType;
    @Size(max = 40)
    private String exMake;
    @Size(max = 60)
    private String exModel;
    @Min(1980)
    @Max(2100)
    private Integer exYear;
    @Min(0)
    @Max(2_000_000)
    private Integer exKm;
    @Size(max = 10)
    private String exFuel;
    @Min(1)
    @Max(10)
    private Integer exOwners;
    @Size(max = 20)
    private String exPlate;
    @Min(0)
    @Max(500_000_000)
    private Long exExpected;
    private Long exListingId;
    @Size(max = 300)
    private String note;
    /** The seller's answer. */
    @Min(0)
    @Max(500_000_000)
    private Long value;
}
