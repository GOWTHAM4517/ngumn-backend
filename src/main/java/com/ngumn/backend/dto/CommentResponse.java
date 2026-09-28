package com.ngumn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A comment as the person looking sees it: the author's first name only,
 * whether it's theirs (they can delete it), and whether the author is the
 * person who made the report (shown as "Reporter"). The driver a
 * rule-breaker report is about never sees who reported them - the
 * reporter's comments show just "Reporter" to them.
 */
@Data
@AllArgsConstructor
public class CommentResponse {
    private Long id;
    private String targetType;
    private Long targetId;
    private Long authorId;
    private String authorName;
    private String text;
    private LocalDateTime createdAt;
    private Boolean mine;
    private Boolean byReporter;
    /** The person looking may delete it: their own, or any comment on their own report. */
    private Boolean canDelete;
}
