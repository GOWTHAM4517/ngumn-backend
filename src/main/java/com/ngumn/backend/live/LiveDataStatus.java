package com.ngumn.backend.live;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The state of one live data source (OpenStreetMap roads, Open-Meteo
 * weather...): when it last worked, when it was last tried, what it brought
 * in. Shown in the Control Centre so anyone can see the data is real and
 * fresh.
 */
@Entity
@Table(name = "live_data_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveDataStatus {

    /** ROADS, WEATHER... */
    @Id
    @Column(length = 30)
    private String source;

    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastAttemptAt;

    /** "OK", "FAILED", "RUNNING". */
    @Column(length = 20)
    private String state;

    /** Plain words: "18,422 roads, 1,203 with posted limits" or the error. */
    @Column(length = 500)
    private String message;

    /** Rows / readings brought in by the last success. */
    private Long items;

    /** Where it came from, e.g. the Overpass server used. */
    @Column(length = 200)
    private String origin;
}
