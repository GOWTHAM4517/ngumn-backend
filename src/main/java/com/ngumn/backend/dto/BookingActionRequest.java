package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Answering or running a test drive: decline / cancel (reason), another
 * time (at), start (code, latitude, longitude), rate (stars, asDescribed,
 * comment).
 */
@Data
public class BookingActionRequest {
    @Size(max = 200)
    private String reason;

    private LocalDateTime at;

    @Size(max = 4)
    private String code;

    private Double latitude;
    private Double longitude;

    @Min(1)
    @Max(5)
    private Integer stars;

    private Boolean asDescribed;

    @Size(max = 300)
    private String comment;
}
