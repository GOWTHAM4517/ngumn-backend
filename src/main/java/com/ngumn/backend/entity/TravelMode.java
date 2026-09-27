package com.ngumn.backend.entity;

/**
 * How someone is getting around right now. People switch this in the app
 * whenever it changes - walking to the bus stop, riding a bike, driving a
 * car - so warnings, rule checks and how they appear on other people's
 * maps always match what they're actually doing.
 *
 * Stored next to the older VehicleType (kept in sync, see toVehicleType)
 * rather than replacing it, so rule zones for vehicle types keep working
 * and apps from before this change still read a sensible type.
 */
public enum TravelMode {
    WALK("person walking", "a person walking"),
    CYCLE("cycle", "a cycle"),
    BIKE("bike", "a bike"),
    AUTO("auto", "an auto"),
    CAR("car", "a car"),
    BUS("bus", "a bus"),
    TRUCK("truck", "a truck"),
    EMERGENCY("emergency vehicle", "an emergency vehicle");

    private final String noun;
    private final String withArticle;

    TravelMode(String noun, String withArticle) {
        this.noun = noun;
        this.withArticle = withArticle;
    }

    /** "car", "bike", "person walking"... */
    public String noun() {
        return noun;
    }

    /** "a car", "an auto", "a person walking"... */
    public String withArticle() {
        return withArticle;
    }

    /** On foot: no vehicle rules (speed limits, one-way roads) apply. */
    public boolean onFoot() {
        return this == WALK;
    }

    /** Can break a speed limit - walking and cycling can't in practice. */
    public boolean motorised() {
        return this != WALK && this != CYCLE;
    }

    /** The older vehicle type this maps to (rule zones for types use it). */
    public VehicleType toVehicleType() {
        return switch (this) {
            case BIKE -> VehicleType.BIKE;
            case CAR -> VehicleType.CAR;
            case BUS -> VehicleType.BUS;
            case TRUCK -> VehicleType.TRUCK;
            case EMERGENCY -> VehicleType.EMERGENCY;
            default -> VehicleType.OTHER; // WALK, CYCLE, AUTO
        };
    }

    /** Best guess for vehicles saved before travel modes existed. */
    public static TravelMode from(VehicleType type) {
        if (type == null) return CAR;
        return switch (type) {
            case BIKE -> BIKE;
            case BUS -> BUS;
            case TRUCK -> TRUCK;
            case EMERGENCY -> EMERGENCY;
            default -> CAR; // CAR, OTHER
        };
    }
}
