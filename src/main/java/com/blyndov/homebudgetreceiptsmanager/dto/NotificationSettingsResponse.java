package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;

public record NotificationSettingsResponse(
    String email,
    String telegramChatId,
    boolean telegramConnected,
    java.time.Instant telegramConnectedAt,
    NotificationChannel preferredNotificationChannel
) {
}
