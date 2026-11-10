package com.ngumn.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Someone looking for a vehicle like this one was told about it
 * (MarketRoadService): it was next to them on the road (ROAD), they passed
 * the dealer that has it (PLACE), or it was just listed (NEW). Keeps them
 * from being told twice, and shows the seller how many interested buyers
 * saw it.
 */
@Entity
@Table(name = "listing_sightings", indexes = {
        @Index(name = "idx_lsight_listing", columnList = "listing_id"),
        @Index(name = "idx_lsight_buyer", columnList = "buyer_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingSighting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    /** ROAD, PLACE or NEW. */
    @Column(nullable = false, length = 6)
    private String kind;

    private Double distanceM;

    @Column(nullable = false)
    private LocalDateTime at;
}
