package com.ngumn.backend.dto;

import com.ngumn.backend.entity.TravelMode;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Changes to your own vehicle from the app. Anything left out (null) stays
 * as it is; an empty plateNumber removes the plate.
 */
@Data
public class VehicleUpdateRequest {

    /** How you're travelling now: WALK, CYCLE, BIKE, AUTO, CAR, BUS, TRUCK or EMERGENCY. */
    private TravelMode travelMode;

    @Size(max = 20)
    private String plateNumber;
}
