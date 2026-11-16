package com.ngumn.backend.ml;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A learned model saved in the database so it keeps what it learned across
 * restarts: its weights (JSON), how many examples it has learned from, and
 * its running score on examples it had not seen yet.
 */
@Entity
@Table(name = "ml_models")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlModelState {

    @Id
    @Column(length = 40)
    private String name;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String weightsJson;

    private Long samples;

    /** Predictions that matched the real outcome (judged before learning from it). */
    private Long correct;

    /** Sum of log-loss over those predictions. */
    private Double logLossSum;

    private LocalDateTime updatedAt;
}
