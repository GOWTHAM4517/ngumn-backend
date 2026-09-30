package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Column(nullable = false, length = 300)
    private String message;

    private Double latitude;

    private Double longitude;

    /** The hazard report this alert is about, if any - lets the app open it, and like or comment from the notification. */
    @Column(name = "report_id")
    private Long reportId;

    /** The rule-breaker report this alert is about, if any. */
    @Column(name = "complaint_id")
    private Long complaintId;

    /** The help request this alert is about, if any (see Incident) - the app opens it. */
    @Column(name = "incident_id")
    private Long incidentId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean acknowledged = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.acknowledged == null) this.acknowledged = false;
    }
}
