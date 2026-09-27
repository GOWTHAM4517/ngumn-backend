package com.ngumn.backend.entity;

/** One-tap feedback on someone else's report. */
public enum FeedbackKind {
    /** "Helpful" - the heart. Anyone but the reporter; tap again to take it back. */
    HELPFUL,
    /** "Not there anymore" - enough of these clear the report early. */
    GONE
}
