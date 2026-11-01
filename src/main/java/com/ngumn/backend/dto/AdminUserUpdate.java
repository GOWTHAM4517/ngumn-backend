package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ResponderTeam;
import com.ngumn.backend.entity.Role;
import lombok.Data;

/** Admin dashboard > Users: any field left null is not changed. */
@Data
public class AdminUserUpdate {
    private Boolean active;
    private Role role;
    /** Make a responder (with unitName) ... */
    private ResponderTeam team;
    private String unitName;
    /** ... or back to a citizen. */
    private Boolean clearTeam;
    /** Reset the password (8+ characters). */
    private String newPassword;
}
