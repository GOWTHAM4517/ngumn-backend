package com.ngumn.backend.dto;

import com.ngumn.backend.entity.IncidentOutcome;
import com.ngumn.backend.entity.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A responder moving a request on: AT_SCENE, RESOLVED (with an outcome) or FALSE_REPORT - or OPEN to hand it back. */
@Data
public class IncidentUpdateRequest {

    @NotNull
    private IncidentStatus status;

    /** With RESOLVED: what was done. */
    private IncidentOutcome outcome;

    /** A short note for the person who asked. */
    @Size(max = 300)
    private String note;
}
