package com.ngumn.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** The admin adds or changes an official complaint channel (ComplaintFilingService.adminSave). */
@Data
public class AuthorityChannelRequest {
    @Size(max = 80)
    private String name;
    /** POLICE, AMBULANCE, FIRE, MUNICIPAL, ELECTRICITY - blank for every department. */
    private String team;
    /** Comma-separated kinds (POTHOLE,GARBAGE...) - blank for all of the department's. */
    @Size(max = 400)
    private String types;
    /** CITY, STATE, NATIONAL or EMERGENCY. */
    private String level;
    @Size(max = 120)
    private String email;
    @Size(max = 20)
    private String phone;
    @Size(max = 20)
    private String whatsapp;
    @Size(max = 20)
    private String sms;
    @Size(max = 200)
    private String portalUrl;
    @Size(max = 200)
    private String note;
    private Boolean autoEmail;
    private Boolean active;
    private Integer sortOrder;
    private Double centerLatitude;
    private Double centerLongitude;
    private Double radiusKm;
}
