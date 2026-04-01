package com.blyndov.homebudgetreceiptsmanager.dto;

import java.time.Instant;

public record TelegramConnectionStatusResponse(
    boolean connected,
    Instant connectedAt,
    String botUsername,
    String pendingDeepLink,
    Instant pendingExpiresAt
) {
}
