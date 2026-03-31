package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NotificationSettingsResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.UpdateNotificationSettingsRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.exception.InvalidNotificationSettingsException;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserNotificationSettingsService {

    private final AuthService authService;
    private final UserRepository userRepository;

    public UserNotificationSettingsService(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    public NotificationSettingsResponse getCurrentUserNotificationSettings() {
        return mapToResponse(authService.getCurrentAuthenticatedUser());
    }

    @Transactional
    public NotificationSettingsResponse updateCurrentUserNotificationSettings(
        UpdateNotificationSettingsRequest request
    ) {
        User user = authService.getCurrentAuthenticatedUser();
        NotificationChannel preferredChannel = request.preferredNotificationChannel();
        String telegramChatId = applyTelegramChatIdUpdate(user.getTelegramChatId(), request.telegramChatId());

        if (preferredChannel == NotificationChannel.TELEGRAM && telegramChatId == null) {
            throw new InvalidNotificationSettingsException(
                "Telegram chat id is required when preferred notification channel is TELEGRAM"
            );
        }

        user.setPreferredNotificationChannel(preferredChannel);
        user.setTelegramChatId(telegramChatId);

        return mapToResponse(userRepository.save(user));
    }

    private NotificationSettingsResponse mapToResponse(User user) {
        return new NotificationSettingsResponse(
            user.getEmail(),
            user.getTelegramChatId(),
            user.getPreferredNotificationChannel()
        );
    }

    private String applyTelegramChatIdUpdate(String currentChatId, String requestedChatId) {
        if (requestedChatId == null) {
            return currentChatId;
        }

        String normalized = requestedChatId.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
