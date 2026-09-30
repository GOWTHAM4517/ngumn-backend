package com.ngumn.backend.dto;

import com.ngumn.backend.entity.ResponderTeam;
import com.ngumn.backend.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private Long userId;
    private String name;
    private String email;
    private Role role;
    private Integer rewardPoints;
    /** POLICE / AMBULANCE / MUNICIPAL / ELECTRICITY for responder accounts, else null. */
    private ResponderTeam responderTeam;
    /** "Traffic Police - Benz Circle" for responder accounts, else null. */
    private String unitName;
}
