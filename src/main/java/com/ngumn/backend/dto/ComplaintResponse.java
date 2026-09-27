package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ReportStatus;
import com.ngumn.backend.entity.ViolationComplaint;
import com.ngumn.backend.entity.ViolationType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A rule-breaker complaint as the app sees it. Like hazard reports it
 * carries the vote counts, confirmations needed, the reporter's trust and
 * the caller's own vote, and how many people found it helpful (helpfulByMe:
 * whether the caller did). accusedIsMe tells the owner of the reported
 * vehicle that it's about them (they can't vote on it).
 */
@Data
@AllArgsConstructor
public class ComplaintResponse {
    private Long id;
    private ViolationType type;
    private ReportStatus status;
    private Long reporterId;
    private String reporterName;
    private Integer reporterTrust;
    private Long accusedVehicleId;
    private String accusedVehicleCode;
    private String plateNumber;
    private String description;
    private Double latitude;
    private Double longitude;
    private Integer confirmations;
    private Integer denials;
    private Integer confirmationsNeeded;
    private Boolean myVote;
    private Boolean accusedIsMe;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
    private Integer helpfulCount;
    private Boolean helpfulByMe;

    public static ComplaintResponse from(ViolationComplaint c, Integer reporterTrust, Integer confirmationsNeeded,
                                         Boolean myVote, Long viewerId) {
        return from(c, reporterTrust, confirmationsNeeded, myVote, viewerId, false);
    }

    public static ComplaintResponse from(ViolationComplaint c, Integer reporterTrust, Integer confirmationsNeeded,
                                         Boolean myVote, Long viewerId, boolean helpfulByMe) {
        boolean accusedIsMe = viewerId != null && c.getAccusedVehicle() != null
                && c.getAccusedVehicle().getOwner() != null
                && viewerId.equals(c.getAccusedVehicle().getOwner().getId());
        // Others see only the reporter's first name; the reported driver
        // doesn't see who reported them.
        String reporterName = null;
        if (!accusedIsMe && c.getReporter() != null && c.getReporter().getName() != null) {
            String full = c.getReporter().getName().trim();
            int space = full.indexOf(' ');
            reporterName = space > 0 ? full.substring(0, space) : full;
        }
        return new ComplaintResponse(
                c.getId(), c.getType(), c.getStatus(),
                accusedIsMe ? null : (c.getReporter() != null ? c.getReporter().getId() : null),
                reporterName,
                reporterTrust,
                c.getAccusedVehicle() != null ? c.getAccusedVehicle().getId() : null,
                c.getAccusedVehicle() != null ? c.getAccusedVehicle().getVehicleCode() : null,
                c.getPlateNumber(), c.getDescription(), c.getLatitude(), c.getLongitude(),
                c.getConfirmations() != null ? c.getConfirmations() : 0,
                c.getDenials() != null ? c.getDenials() : 0,
                confirmationsNeeded, myVote, accusedIsMe,
                c.getCreatedAt(), c.getDecidedAt(),
                c.getHelpfulCount() != null ? c.getHelpfulCount() : 0,
                helpfulByMe
        );
    }
}
