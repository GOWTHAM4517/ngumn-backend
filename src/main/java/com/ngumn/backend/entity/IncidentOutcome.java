package com.ngumn.backend.entity;

/** What the responder did - chosen when they close a help request as RESOLVED. */
public enum IncidentOutcome {
    MOVED_TO_SAFE_SPOT("The vehicle was moved to a safe spot"),
    TOW_CALLED("A tow truck was called"),
    ROAD_CLEARED("The road is clear again"),
    TAKEN_TO_HOSPITAL("Taken to hospital"),
    TREATED_AT_SCENE("Treated at the scene"),
    SITUATION_HANDLED("The situation was handled"),
    FIXED("It has been fixed"),
    NOTHING_FOUND("Nothing was found at the spot");

    private final String label;

    IncidentOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
