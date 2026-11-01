package com.ngumn.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** How well a resolved request or complaint was handled (the person who asked). */
@Data
public class RatingRequest {
    @NotNull
    @Min(1)
    @Max(5)
    private Integer stars;

    @Size(max = 300)
    private String comment;
}
