package com.blyndov.homebudgetreceiptsmanager.dto;

import java.time.Instant;

public record TelegramConnectSessionResponse(
    String botUsername,
    String deepLink,
    Instant expiresAt
) {
}
