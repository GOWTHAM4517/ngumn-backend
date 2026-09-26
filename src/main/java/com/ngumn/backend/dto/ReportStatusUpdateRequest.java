package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ReportStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReportStatusUpdateRequest {

    @NotNull
    private ReportStatus status;
}
