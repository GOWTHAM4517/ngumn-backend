package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Reporting a vehicle for sale: FAKE, SCAM, STOLEN, WRONG_DETAILS, ALREADY_SOLD, OTHER. */
@Data
public class ListingReportRequest {
    @NotBlank
    @Size(max = 14)
    private String reason;

    @Size(max = 300)
    private String note;
}
