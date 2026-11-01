package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin dashboard > Broadcast: a message to everyone, or everyone within
 * radiusMeters of a point ("Road closed at Benz Circle till 6 pm").
 */
@Data
public class BroadcastRequest {
    @NotBlank
    @Size(max = 300)
    private String message;
    private Double latitude;
    private Double longitude;
    private Double radiusMeters;
    /** HIGH, MEDIUM or LOW. */
    private String level;
}
