package com.ngumn.backend.dto;

import com.ngumn.backend.entity.TravelMode;
import com.ngumn.backend.entity.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VehicleCreateRequest {

    /** Internal id for the vehicle (the app makes one up) - never shown to people. */
    @NotBlank
    private String vehicleCode;

    /** Older apps send only this; newer ones send travelMode (the type is then worked out from it). */
    private VehicleType vehicleType;

    /** How the owner is travelling: WALK, CYCLE, BIKE, AUTO, CAR, BUS, TRUCK or EMERGENCY. */
    private TravelMode travelMode;

    @Size(max = 20)
    private String plateNumber;
}
