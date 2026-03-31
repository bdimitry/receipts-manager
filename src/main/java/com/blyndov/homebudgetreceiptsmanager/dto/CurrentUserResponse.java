package com.blyndov.homebudgetreceiptsmanager.dto;

import java.time.Instant;

public record CurrentUserResponse(Long id, String email, Instant createdAt) {
}
