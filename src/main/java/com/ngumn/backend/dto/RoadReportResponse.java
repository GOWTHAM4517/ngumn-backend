package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.ReportType;
import com.ngumn.backend.entity.RoadReport;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A hazard report as the app sees it, including community verification:
 * confirmations / denials so far, how many confirmations it needs,
 * the reporter's trust score, and myVote (true / false / null = not
 * voted) for the person asking.
 *
 * Also: whether it's still on the road (active) and when it clears by
 * itself (expiresAt) or why it ended (endReason: EXPIRED, CLEARED,
 * REJECTED), plus the one-tap feedback - how many found it helpful and
 * said it's not there anymore, and whether the person asking did.
 *
 * Reactions, Facebook-style: likeCount (the same as helpfulCount - a like
 * is "helpful"), dislikeCount, myReaction ("LIKE", "DISLIKE" or null) and
 * commentCount. helpfulCount / helpfulByMe stay for older apps.
 */
@Data
@AllArgsConstructor
public class RoadReportResponse {
    private Long id;
    private ReportType type;
    private String description;
    private Double latitude;
    private Double longitude;
    private String imageUrl;
    private ReportStatus status;
    private Long reporterId;
    private String reporterName;
    private Integer reporterTrust;
    private Integer confirmations;
    private Integer denials;
    private Integer confirmationsNeeded;
    private Boolean myVote;
    private LocalDateTime timestamp;
    private LocalDateTime decidedAt;
    private Boolean active;
    private LocalDateTime expiresAt;
    private LocalDateTime clearedAt;
    private String endReason;
    private Integer helpfulCount;
    private Boolean helpfulByMe;
    private Integer goneCount;
    private Boolean goneByMe;
    private Integer likeCount;
    private Integer dislikeCount;
    private String myReaction;
    private Integer commentCount;

    public static RoadReportResponse from(RoadReport r) {
        return from(r, null, null, null, false, false, LocalDateTime.now());
    }

    public static RoadReportResponse from(RoadReport r, Integer reporterTrust, Integer confirmationsNeeded, Boolean myVote) {
        return from(r, reporterTrust, confirmationsNeeded, myVote, false, false, LocalDateTime.now());
    }

    public static RoadReportResponse from(RoadReport r, Integer reporterTrust, Integer confirmationsNeeded, Boolean myVote,
                                          boolean helpfulByMe, boolean goneByMe, LocalDateTime now) {
        return from(r, reporterTrust, confirmationsNeeded, myVote, helpfulByMe, false, goneByMe, now);
    }

    public static RoadReportResponse from(RoadReport r, Integer reporterTrust, Integer confirmationsNeeded, Boolean myVote,
                                          boolean likedByMe, boolean dislikedByMe, boolean goneByMe, LocalDateTime now) {
        int likes = r.getHelpfulCount() != null ? r.getHelpfulCount() : 0;
        return new RoadReportResponse(
                r.getId(), r.getType(), r.getDescription(), r.getLatitude(), r.getLongitude(),
                r.getImageUrl(), r.getStatus(),
                r.getReporter() != null ? r.getReporter().getId() : null,
                r.getReporter() != null ? r.getReporter().getName() : null,
                reporterTrust,
                r.getConfirmations() != null ? r.getConfirmations() : 0,
                r.getDenials() != null ? r.getDenials() : 0,
                confirmationsNeeded,
                myVote,
                r.getTimestamp(),
                r.getDecidedAt(),
                r.isActiveAt(now),
                r.effectiveExpiresAt(),
                r.getClearedAt(),
                r.endReasonAt(now),
                likes,
                likedByMe,
                r.getGoneCount() != null ? r.getGoneCount() : 0,
                goneByMe,
                likes,
                r.getDislikeCount() != null ? r.getDislikeCount() : 0,
                likedByMe ? "LIKE" : dislikedByMe ? "DISLIKE" : null,
                r.getCommentCount() != null ? r.getCommentCount() : 0
        );
    }
}
