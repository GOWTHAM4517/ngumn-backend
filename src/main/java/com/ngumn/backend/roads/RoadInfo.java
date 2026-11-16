package com.ngumn.backend.roads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The road a vehicle is on right now, as the app shows it: "Bandar Road -
 * limit 70 km/h (law, city road)". Sent back with every location update.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadInfo {
    /** OSM way id, null when the point isn't on a mapped road. */
    private Long roadId;
    /** True when matched to a mapped road. */
    private boolean mapped;
    /** Name to show: the road's name, else its number, else its kind. */
    private String name;
    /** Road number, e.g. NH16. */
    private String ref;
    /** "National highway", "Main road", "City road"... */
    private String roadType;
    /** Legal category: expressway, 4-lane divided road, city / other road. */
    private String category;
    /** The limit for this person's vehicle here, km/h (null when walking or cycling). */
    private Integer limitKmh;
    /** SIGN (posted limit on the map), LAW (class limit from the notification) or ZONE (set by the admin / police). */
    private String limitSource;
    /** One line explaining the limit, e.g. "Legal limit for a car on a city road (MoRTH S.O. 1522(E))". */
    private String limitNote;
    /** Speed above which it counts as over-speeding (limit + 5%). */
    private Double overAtKmh;
    /** The road is one-way here. */
    private boolean oneway;
    /** Moving against the one-way direction right now. */
    private boolean wrongWay;
    /** On a road this vehicle may not use right now (pedestrian street, vehicle ban). */
    private boolean noEntry;
    /** Why, e.g. "Pedestrian street - no vehicles" or "No heavy vehicles 08:00-11:00". */
    private String restriction;
    /** Travelling in the road's drawing direction (used to keep the two directions of a two-way road apart). */
    private boolean forward;
    /** Metres from the road's centre line. */
    private Double distanceMeters;
}
