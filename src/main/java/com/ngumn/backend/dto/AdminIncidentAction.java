package com.ngumn.backend.dto;

import com.ngumn.backend.entity.IncidentOutcome;
import com.ngumn.backend.entity.IncidentStatus;
import lombok.Data;

/** Admin dashboard: assign / change status / reopen a help request or complaint. */
@Data
public class AdminIncidentAction {
    /** Assign to this responder account (same team). */
    private Long responderId;
    /** OPEN (reopen), RESOLVED, FALSE_REPORT, CANCELLED. */
    private IncidentStatus status;
    private IncidentOutcome outcome;
    private String note;
}
