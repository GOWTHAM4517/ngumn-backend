package com.ngumn.backend.entity;

/**
 * What someone needs help with - or wants fixed - which team gets it, and how
 * urgent it is to start with (see IncidentService.priorityOf for the rest of
 * the score).
 *
 * Two kinds:
 * <ul>
 *   <li><b>Help requests</b> (complaint = false): something happening now -
 *       a crash, a breakdown, a fire. Responders accept and go to the spot.</li>
 *   <li><b>Complaints</b> (complaint = true): an everyday inconvenience the
 *       authority has to fix - a pothole, garbage, a power cut. Each has a
 *       deadline (slaHours); EscalationService raises it with the admin and the
 *       department again when it's missed.</li>
 * </ul>
 */
public enum IncidentType {
    // ---- help requests (now) ----
    BREAKDOWN("Vehicle broken down", "breakdown", ResponderTeam.POLICE, 30, true),
    ACCIDENT("Accident", "accident", ResponderTeam.POLICE, 50, true),
    MEDICAL("Someone needs an ambulance", "medical emergency", ResponderTeam.AMBULANCE, 70, false),
    FIRE("Fire", "fire report", ResponderTeam.FIRE, 80, true),
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
    STREETLIGHT_OUT("Streetlight not working", "streetlight report", ResponderTeam.ELECTRICITY, 10, false),
    /** Reported from Car guard (CarGuardService) - the police get the car, its last place and a live link. */
    VEHICLE_THEFT("Vehicle stolen", "stolen-vehicle report", ResponderTeam.POLICE, 65, false),

    // ---- complaints (to be fixed by a deadline) ----
    POTHOLE("Pothole or damaged road", "pothole complaint", ResponderTeam.MUNICIPAL, 20, true, 72),
    ROAD_DIGGING("Road dug up and left open", "road-digging complaint", ResponderTeam.MUNICIPAL, 30, true, 48),
    GARBAGE("Garbage not cleared", "garbage complaint", ResponderTeam.MUNICIPAL, 15, false, 48),
    DRAINAGE("Blocked drain or sewage overflow", "drainage complaint", ResponderTeam.MUNICIPAL, 30, false, 24),
    WATER_SUPPLY("No water supply or pipe leak", "water-supply complaint", ResponderTeam.MUNICIPAL, 25, false, 24),
    STRAY_ANIMALS("Stray dogs or animals", "stray-animal complaint", ResponderTeam.MUNICIPAL, 20, false, 72),
    ENCROACHMENT("Footpath or road blocked by encroachment", "encroachment complaint", ResponderTeam.MUNICIPAL, 15, true, 168),
    POWER_CUT("Power cut", "power-cut complaint", ResponderTeam.ELECTRICITY, 30, false, 12),
    ILLEGAL_PARKING("Illegal parking blocking traffic", "parking complaint", ResponderTeam.POLICE, 20, true, 6),
    TRAFFIC_JAM("Traffic jam that needs police", "traffic complaint", ResponderTeam.POLICE, 30, true, 2),
    NOISE("Noise nuisance", "noise complaint", ResponderTeam.POLICE, 15, false, 12);

    private final String label;
    private final String noun;
    private final ResponderTeam team;
    private final int basePriority;
    /** Slows traffic, so busier at peak hours (see IncidentService.priorityOf). */
    private final boolean traffic;
    /** Hours the department has to fix a complaint; 0 for help requests. */
    private final int slaHours;

    IncidentType(String label, String noun, ResponderTeam team, int basePriority, boolean traffic) {
        this(label, noun, team, basePriority, traffic, 0);
    }

    IncidentType(String label, String noun, ResponderTeam team, int basePriority, boolean traffic, int slaHours) {
        this.label = label;
        this.noun = noun;
        this.team = team;
        this.basePriority = basePriority;
        this.traffic = traffic;
        this.slaHours = slaHours;
    }

    /** "Vehicle broken down" - how responders see it. */
    public String label() { return label; }
    /** "breakdown" - "your breakdown request". */
    public String noun() { return noun; }
    public ResponderTeam team() { return team; }
    public int basePriority() { return basePriority; }
    public boolean traffic() { return traffic; }
    public int slaHours() { return slaHours; }
    /** A complaint to be fixed by a deadline, not help needed right now. */
    public boolean complaint() { return slaHours > 0; }
}
