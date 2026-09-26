package com.ngumn.backend.dto;

import com.ngumn.backend.entity.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VehicleCreateRequest {

    @NotBlank
    private String vehicleCode;

    @NotNull
    private VehicleType vehicleType;
}
