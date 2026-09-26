package com.ngumn.backend.entity;

/**
 * Place-based traffic rules (besides speed limits, which live in
 * RoadSpeedLimit).
 */
public enum RuleZoneType {
    /** A road segment that may only be driven from its start point towards its end point. */
    ONE_WAY,
    /** An area vehicles must not drive into (pedestrian zone, bus-only road, school gate...). */
    NO_ENTRY
}
