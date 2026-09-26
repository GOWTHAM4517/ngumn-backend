package com.ngumn.backend.dto;

import com.ngumn.backend.entity.Reward;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class RewardResponse {
    private Long id;
    private Integer points;
    private String reason;
    private LocalDateTime createdAt;

    public static RewardResponse from(Reward r) {
        return new RewardResponse(r.getId(), r.getPoints(), r.getReason(), r.getCreatedAt());
    }
}
