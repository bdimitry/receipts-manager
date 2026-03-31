package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import jakarta.validation.constraints.NotNull;

public record UpdateNotificationSettingsRequest(
    @NotNull(message = "must not be null")
    NotificationChannel preferredNotificationChannel,
    String telegramChatId
) {
}
