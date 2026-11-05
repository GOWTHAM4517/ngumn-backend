package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A place: where a stolen car was last seen (report / "I saw it"), or a
 * position sent by the owner's phone standing in for a tracker (a demo).
 */
@Data
public class CarPointRequest {
    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private Double speedKmh;
    private Double heading;

    @Size(max = 200)
    private String note;
}
