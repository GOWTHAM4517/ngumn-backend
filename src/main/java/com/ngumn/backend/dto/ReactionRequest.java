package com.ngumn.backend.dto;

import lombok.Data;

/** Like, dislike or neither: {"reaction": "LIKE" | "DISLIKE" | "NONE"}. */
@Data
public class ReactionRequest {
    private String reaction;
}
