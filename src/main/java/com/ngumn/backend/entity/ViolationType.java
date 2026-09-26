package com.ngumn.backend.entity;

/**
 * Traffic-rule violations NGUMN knows about. The first four can be
 * detected automatically (by the server's RuleMonitorService from live
 * location, or by Drive Guard on the driver's phone); every type can be
 * reported by other road users as a complaint and confirmed by the
 * community. The app's Road Rules screen shows the matching Motor
 * Vehicles Act (1988, amended 2019) section and fine for each.
 */
public enum ViolationType {
    /** Faster than the limit of a mapped speed zone (MV Act s.183). */
    OVERSPEED,
    /** Driving against the permitted direction - one-way road / wrong side (s.184). */
    WRONG_WAY,
    /** Driving into a no-entry zone (s.177A, road regulations). */
    NO_ENTRY,
    /** More people on board than allowed, e.g. triple riding (s.194A / s.194C). */
    OVERLOAD,
    /** Jumping a red signal (s.184). */
    SIGNAL_JUMP,
    /** Riding without a helmet (s.194D). */
    NO_HELMET,
    /** Driving without a seat belt (s.194B). */
    NO_SEATBELT,
    /** Using a handheld phone while driving (s.184). */
    PHONE_USE,
    /** Rash / dangerous driving, zig-zagging, racing (s.184). */
    DANGEROUS_DRIVING,
    /** Not giving way to an ambulance or other emergency vehicle (s.194E). */
    EMERGENCY_BLOCKING
}
