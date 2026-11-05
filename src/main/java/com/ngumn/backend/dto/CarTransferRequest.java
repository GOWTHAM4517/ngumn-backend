package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A showroom hands a sold car (and its tracker) to the buyer's Raksio account: their email or mobile number. */
@Data
public class CarTransferRequest {
    @NotBlank
    @Size(max = 120)
    private String to;
}
