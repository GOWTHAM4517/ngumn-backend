package com.ngumn.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** The whole list, in calling order (first = called first). At least 3, at most 5. */
@Data
public class ContactsSaveRequest {
    @NotNull
    @Size(min = 3, max = 5, message = "Add at least 3 and at most 5 emergency contacts")
    private List<@Valid ContactInput> contacts;
}
