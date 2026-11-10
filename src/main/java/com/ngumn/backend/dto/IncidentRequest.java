package com.ngumn.backend.dto;

import com.ngumn.backend.entity.IncidentType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** "I need help": what, where, and a couple of quick answers (all optional but the first three). */
@Data
public class IncidentRequest {

    @NotNull
    private IncidentType type;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    /** Breakdowns and accidents: BIKE, CYCLE, AUTO, CAR, BUS, TRUCK, OTHER. */
    private String vehicleKind;

    /** Breakdowns and accidents: is it blocking traffic - YES, PARTLY or NO. */
    private String blocking;

    /** Accidents: how many people are hurt (0 = nobody). */
    @Min(0)
    @Max(50)
    private Integer injured;

    @Size(max = 500)
    private String description;

    /**
     * Complaints filed officially (email to the department, ComplaintFilingService):
     * may the department see the person's name and phone number? Off unless sent as true.
     */
    private Boolean shareContact;
}
