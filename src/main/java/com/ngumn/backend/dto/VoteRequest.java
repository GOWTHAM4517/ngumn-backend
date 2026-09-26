package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * A "true" / "false" answer about a report or complaint. The voter's
 * current position is optional; when sent, votes from far away are
 * refused (only people who could have seen it should answer).
 */
@Data
public class VoteRequest {

    @NotNull
    private Boolean agree;

    private Double latitude;

    private Double longitude;
}
