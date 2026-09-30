package com.ngumn.backend.entity;

/**
 * Who answers a help request (see Incident). An account belongs to one team
 * only if an admin made it a responder account (Admin dashboard >
 * Responders) - nobody can pick this at sign-up, or anyone could pose as
 * police. Everyone else (responderTeam null) is a citizen.
 */
public enum ResponderTeam {
    /** Traffic and law-and-order police: breakdowns, accidents, signals, disturbances, "I feel unsafe". */
    POLICE("Police"),
    /** 108 / hospital ambulances: people hurt or taken ill. */
    AMBULANCE("Ambulance"),
    /** The municipality: open manholes, fallen trees, waterlogging, cattle on the road. */
    MUNICIPAL("Municipality"),
    /** The electricity department: live wires down, streetlights out. */
    ELECTRICITY("Electricity");

    private final String label;

    ResponderTeam(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
