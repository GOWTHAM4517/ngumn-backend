package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One attempt to reach one emergency contact about an SosEvent: in the app,
 * by SMS or by an automatic voice call - and whether it worked. Shown on the
 * crash screen ("Mother - SMS sent, call placed") and on the admin dashboard.
 */
@Entity
@Table(name = "sos_deliveries", indexes = @Index(name = "idx_delivery_sos", columnList = "sos_event_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SosDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sos_event_id", nullable = false)
    private Long sosEventId;

    @Column(name = "contact_name", length = 80)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    /** IN_APP, SMS or CALL. */
    @Column(nullable = false, length = 10)
    private String channel;

    /** SENT, FAILED or NOT_SET_UP (no SMS/call provider configured on the server). */
    @Column(nullable = false, length = 12)
    private String status;

    /** Provider id, or why it failed. */
    @Column(length = 300)
    private String detail;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime at;

    @PrePersist
    protected void onCreate() {
        if (this.at == null) this.at = LocalDateTime.now();
    }
}
