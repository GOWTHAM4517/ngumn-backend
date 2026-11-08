package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One photo of a vehicle for sale - a small JPEG the app shrank before
 * sending (about 100-300 KB). Served at /api/market/photo/{token}; the
 * random token is what makes the link work, so it can go into an image tag.
 */
@Entity
@Table(name = "listing_photos", indexes = {
        @Index(name = "idx_photo_listing", columnList = "listing_id"),
        @Index(name = "idx_photo_token", columnList = "token", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(nullable = false, length = 32)
    private String token;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
