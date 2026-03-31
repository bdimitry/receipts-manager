package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;

public record NotificationSettingsResponse(
    String email,
    String telegramChatId,
    NotificationChannel preferredNotificationChannel
) {
}
