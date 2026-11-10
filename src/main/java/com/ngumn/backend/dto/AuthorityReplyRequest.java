package com.ngumn.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** A department's answer through the reply link in its complaint email. */
@Data
public class AuthorityReplyRequest {
    /** ACCEPT (taken up), RESOLVE (fixed) or REJECT (not ours). */
    private String action;
    /** Who's answering - "Ravi, AE Ward 12" (optional). */
    @Size(max = 80)
    private String name;
    @Size(max = 300)
    private String note;
    /** Their own complaint number, if they gave it one. */
    @Size(max = 60)
    private String officialRef;
}
