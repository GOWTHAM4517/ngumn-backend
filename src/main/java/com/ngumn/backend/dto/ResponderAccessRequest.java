package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ResponderTeam;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** "I'm police / ambulance / fire / a department officer" - waits for an admin to approve. */
@Data
public class ResponderAccessRequest {
    @NotNull
    private ResponderTeam team;

    /** "Traffic Police - Benz Circle". */
    @NotBlank
    @Size(max = 120)
    private String unitName;

    /** Badge / employee number, office phone - anything the admin can check. */
    @NotBlank
    @Size(max = 300)
    private String note;
}
