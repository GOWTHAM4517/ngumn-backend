package com.ngumn.backend.roads;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * One real road of the Vijayawada region, as mapped on OpenStreetMap (one
 * OSM "way"). Downloaded by RoadDataService and refreshed every week, so the
 * speed limits and one-way directions Raksio checks are the ones on the map,
 * not values we typed in.
 *
 * Stored: the way's own id, its name / number, its OSM road class
 * ("highway" tag), the posted speed limit if the map has one (maxspeed,
 * maxspeed:forward, maxspeed:backward), whether it is one-way and in which
 * direction, whether it is a divided carriageway, its lanes, and its shape as
 * an encoded polyline (see Polyline).
 */
@Entity
@Table(name = "road_segments", indexes = {
        @Index(name = "idx_road_segments_box", columnList = "minLat,minLng")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoadSegment implements Persistable<Long> {

    /** The OpenStreetMap way id (not generated - the same road keeps its id across refreshes). */
    @Id
    private Long id;

    @Column(length = 160)
    private String name;

    /** Road number, e.g. "NH16" or "SH48". */
    @Column(length = 60)
    private String ref;

    /** OSM road class: motorway, trunk, primary, secondary, tertiary, residential... */
    @Column(nullable = false, length = 30)
    private String highway;

    /** Posted limit in km/h for both directions (null = none on the map). */
    private Integer maxspeedKmh;

    /** Posted limit in the drawing direction only, when the map gives one. */
    private Integer maxspeedForwardKmh;

    /** Posted limit against the drawing direction only, when the map gives one. */
    private Integer maxspeedBackwardKmh;

    /** 0 = two-way, 1 = one-way in the drawing direction, -1 = one-way against it. */
    @Column(nullable = false)
    @Builder.Default
    private Integer oneway = 0;

    /** One-way for motor vehicles but cycles may use both directions (oneway:bicycle=no). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean cyclesBothWays = false;

    /** A carriageway with a median / divider (dual carriageway). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean divided = false;

    private Integer lanes;

    /** The road's shape: Google encoded polyline, precision 5 (about 1 m). */
    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String geometry;

    private Double minLat;
    private Double minLng;
    private Double maxLat;
    private Double maxLng;

    private LocalDateTime importedAt;

    /** Always inserted fresh after a download (the table is replaced), so skip Hibernate's look-up first. */
    @Transient
    @Builder.Default
    private boolean fresh = true;

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
