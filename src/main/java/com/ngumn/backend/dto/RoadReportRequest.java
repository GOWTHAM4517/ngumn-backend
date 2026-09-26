package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ReportType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoadReportRequest {

    @NotNull
    private ReportType type;

    private String description;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private String imageUrl;
}
