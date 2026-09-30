package com.ngumn.backend.entity;

/**
 * What someone needs help with, which team gets it, and how urgent it is to
 * start with (see IncidentService.priorityOf for the rest of the score).
 */
public enum IncidentType {
    BREAKDOWN("Vehicle broken down", "breakdown", ResponderTeam.POLICE, 30, true),
    ACCIDENT("Accident", "accident", ResponderTeam.POLICE, 50, true),
    MEDICAL("Someone needs an ambulance", "medical emergency", ResponderTeam.AMBULANCE, 70, false),
    UNSAFE("Someone feels unsafe", "request for safety", ResponderTeam.POLICE, 60, false),
    DISTURBANCE("Public disturbance", "disturbance report", ResponderTeam.POLICE, 35, false),
    DRUNK_DRIVING("Drunk driving", "drunk-driving report", ResponderTeam.POLICE, 40, true),
    WRONG_SIDE("Wrong-side driving", "wrong-side driving report", ResponderTeam.POLICE, 30, true),
    SIGNAL_DOWN("Traffic signal not working", "signal report", ResponderTeam.POLICE, 30, true),
    OPEN_MANHOLE("Open manhole", "manhole report", ResponderTeam.MUNICIPAL, 40, false),
    FALLEN_TREE("Fallen tree", "fallen-tree report", ResponderTeam.MUNICIPAL, 35, true),
    WATERLOGGING("Waterlogged road", "waterlogging report", ResponderTeam.MUNICIPAL, 25, true),
    CATTLE("Cattle on the road", "cattle report", ResponderTeam.MUNICIPAL, 20, true),
    LIVE_WIRE("Live electric wire on the road", "live-wire report", ResponderTeam.ELECTRICITY, 65, false),
    STREETLIGHT_OUT("Streetlight not working", "streetlight report", ResponderTeam.ELECTRICITY, 10, false);

    private final String label;
    private final String noun;
    private final ResponderTeam team;
    private final int basePriority;
    /** Slows traffic, so busier at peak hours (see IncidentService.priorityOf). */
    private final boolean traffic;

    IncidentType(String label, String noun, ResponderTeam team, int basePriority, boolean traffic) {
        this.label = label;
        this.noun = noun;
        this.team = team;
        this.basePriority = basePriority;
        this.traffic = traffic;
    }

    /** "Vehicle broken down" - how responders see it. */
    public String label() { return label; }
    /** "breakdown" - "your breakdown request". */
    public String noun() { return noun; }
    public ResponderTeam team() { return team; }
    public int basePriority() { return basePriority; }
    public boolean traffic() { return traffic; }
}
