package com.ngumn.backend.entity;

/**
 * A help request's life: OPEN (waiting for a responder) -> ACCEPTED (one is
 * on the way) -> AT_SCENE -> RESOLVED. A responder can also mark it
 * FALSE_REPORT, and the person who asked can CANCEL it while it's open or
 * on the way.
 */
public enum IncidentStatus {
    OPEN,
    ACCEPTED,
    AT_SCENE,
    RESOLVED,
    FALSE_REPORT,
    CANCELLED;

    /** Still needs someone: waiting, on the way or at the scene. */
    public boolean active() {
        return this == OPEN || this == ACCEPTED || this == AT_SCENE;
    }
}
