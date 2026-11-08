package com.ngumn.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A photo of a vehicle for sale: a JPEG or PNG as base64 (at most about 700 KB). */
@Data
public class PhotoRequest {
    @NotBlank
    @Size(max = 1_000_000)
    private String data;

    @Size(max = 30)
    private String contentType;
}
