package com.ngumn.backend.entity;

/**
 * One-tap feedback on someone else's report. Stored as plain text
 * (varchar), so values can be added without a migration.
 */
public enum FeedbackKind {
    /** Like - the thumbs up (was "Helpful", the heart). Anyone but the reporter; tap again to take it back. */
    HELPFUL,
    /** "Not there anymore" - enough of these clear the report early. */
    GONE,
    /** Dislike - the thumbs down: not useful, or not true. Never both a like and a dislike from one person. */
    DISLIKE
}
