package com.blyndov.homebudgetreceiptsmanager.dto;

import java.time.Instant;

public record AdminUserResponse(
    Long id,
    String email,
    String role,
    String authProvider,
    Instant createdAt
) {
}
