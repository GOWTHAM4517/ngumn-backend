package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Marking a vehicle sold: to the buyer of one of its test drives
 * (bookingId), to a Raksio account (to: email or mobile), or to someone
 * outside Raksio (neither). handOverTracker moves its Car guard car and
 * tracker to the buyer's account.
 */
@Data
public class SoldRequest {
    private Long bookingId;

    @Size(max = 120)
    private String to;

    @Min(1000)
    @Max(500_000_000)
    private Long price;

    private Boolean handOverTracker;
}
