package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One way a complaint (or help request) reached an official channel
 * (AuthorityChannel), and what came back:
 *
 * <ul>
 *   <li>EMAIL - sent by Raksio: SENT / FAILED / NOT_SET_UP (no email service
 *       on the server), then ACCEPTED / RESOLVED / REJECTED when the department
 *       uses the reply link in it (token);</li>
 *   <li>CALL, WHATSAPP, SMS, PORTAL - the person opened it from the app:
 *       OPENED; they can add the official complaint number they were given
 *       (officialRef).</li>
 * </ul>
 */
@Entity
@Table(name = "complaint_filings", indexes = {
        @Index(name = "idx_filing_incident", columnList = "incident_id"),
        @Index(name = "idx_filing_token", columnList = "token")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplaintFiling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "channel_name", nullable = false, length = 80)
    private String channelName;

    /** EMAIL, CALL, WHATSAPP, SMS or PORTAL. */
    @Column(nullable = false, length = 10)
    private String method;

    /** SENT, FAILED, NOT_SET_UP, OPENED, ACCEPTED, RESOLVED, REJECTED. */
    @Column(nullable = false, length = 12)
    private String status;

    /** "sent to grievance@…", the email service's message id, why it failed. */
    @Column(length = 300)
    private String detail;

    /** The department's own complaint / grievance number ("PGRS 1234567"). */
    @Column(name = "official_ref", length = 60)
    private String officialRef;

    /** EMAIL: the secret in the department's reply link. */
    @Column(length = 40, unique = true)
    private String token;

    /** Who answered through the reply link ("Ravi, AE Ward 12"). */
    @Column(name = "responder_name", length = 80)
    private String responderName;

    @Column(name = "response_note", length = 300)
    private String responseNote;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime respondedAt;

    /** EMAIL: when a reminder went for a complaint past its deadline. */
    private LocalDateTime remindedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** The department has answered through the link (taken up, fixed or not theirs). */
    public boolean answered() {
        return "ACCEPTED".equals(status) || "RESOLVED".equals(status) || "REJECTED".equals(status);
    }
}
