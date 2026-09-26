package com.ngumn.backend.entity;

/** How a traffic violation was detected. */
public enum ViolationSource {
    /** The server's rule monitor, from the vehicle's live location updates. */
    MONITOR,
    /** Drive Guard on the driver's own phone (e.g. overloading, or a Test Drive). */
    DRIVE_GUARD,
    /** A complaint by another road user that the community confirmed. */
    COMMUNITY
}
