package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Someone reported a vehicle for sale as fake, a scam, stolen... (one per person per listing). */
@Entity
@Table(name = "listing_reports", indexes = {
        @Index(name = "idx_lreport_listing", columnList = "listing_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @ManyToOne
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /** FAKE, SCAM, STOLEN, WRONG_DETAILS, ALREADY_SOLD, OTHER. */
    @Column(nullable = false, length = 14)
    private String reason;

    @Column(length = 300)
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
