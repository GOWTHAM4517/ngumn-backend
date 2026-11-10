package com.ngumn.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** The person opened an official channel from the app (CALL, WHATSAPP, SMS, PORTAL), or adds the official number they got. */
@Data
public class FilingRequest {
    private Long channelId;
    @Size(max = 10)
    private String method;
    /** The department's own complaint number ("PGRS 1234567"). */
    @Size(max = 60)
    private String officialRef;
}
