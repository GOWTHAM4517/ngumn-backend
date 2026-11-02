package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ResponderTeam;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A responder at the spot asks another team (or another unit) to come too. */
@Data
public class BackupRequest {
    @NotNull
    private ResponderTeam team;

    /** "Two people trapped", "Need traffic control". */
    @Size(max = 300)
    private String note;
}
