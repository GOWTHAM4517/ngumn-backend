package com.ngumn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A person's standing in the community: trust score (0-100), a label,
 * how their reports and complaints turned out, how accurate their votes
 * were, and how many confirmations their next report needs.
 */
@Data
@AllArgsConstructor
public class TrustResponse {
    private Long userId;
    private Integer trustScore;
    private String level;
    private Long verifiedReports;
    private Long rejectedReports;
    private Long votesCast;
    private Long votesMatched;
    private Integer confirmationsNeeded;
}
