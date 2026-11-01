package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A person to tell when this user has a crash or presses SOS (at least three,
 * at most five - see ContactService). Kept on the server as well as the phone,
 * so they're told even if the phone is broken in the crash.
 */
@Entity
@Table(name = "emergency_contacts", indexes = {
        @Index(name = "idx_contact_owner", columnList = "owner_id"),
        @Index(name = "idx_contact_phone", columnList = "phone")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 80)
    private String name;

    /** Stored as +91XXXXXXXXXX (see ContactService.normalise). */
    @Column(nullable = false, length = 20)
    private String phone;

    /** "Mother", "Brother", "Friend"... */
    @Column(length = 40)
    private String relation;

    /** 1 = called first. */
    @Column(name = "call_order", nullable = false)
    private Integer position;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
