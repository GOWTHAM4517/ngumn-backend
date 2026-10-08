package com.ngumn.backend.entity;

/**
 * One-tap feedback on someone else's report. Stored as plain text
 * (varchar), so values can be added without a migration.
 */
public enum FeedbackKind {
    /** Like - the thumbs up (was "Helpful", the heart): "it's true". Likes make a report trusted. Anyone but the reporter; tap again to take it back. */
    HELPFUL,
    /** "Not an issue anymore" - enough of these clear the report early, and nobody loses points. */
    GONE,
    /** Dislike - the thumbs down: "it's wrong". More than two take a report down. Never both a like and a dislike from one person. */
    DISLIKE,
    /**
     * Reported the same thing at the same spot - their report was added to
     * this one instead of making a second report (RoadReportService.submit).
     * It also counts as a like. RoadReport.reportCount is 1 + these.
     */
    REPORTED
}
