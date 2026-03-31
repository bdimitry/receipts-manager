package com.blyndov.homebudgetreceiptsmanager.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 1, max = 255) String password
) {
}
