package com.blyndov.homebudgetreceiptsmanager.controller;

import com.blyndov.homebudgetreceiptsmanager.dto.CurrentUserResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.NotificationSettingsResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.UpdateNotificationSettingsRequest;
import com.blyndov.homebudgetreceiptsmanager.service.AuthService;
import com.blyndov.homebudgetreceiptsmanager.service.UserNotificationSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final UserNotificationSettingsService userNotificationSettingsService;

    public UserController(
        AuthService authService,
        UserNotificationSettingsService userNotificationSettingsService
    ) {
        this.authService = authService;
        this.userNotificationSettingsService = userNotificationSettingsService;
    }

    @GetMapping("/me")
    public CurrentUserResponse getCurrentUser() {
        return authService.getCurrentUser();
    }

    @GetMapping("/me/notification-settings")
    public NotificationSettingsResponse getNotificationSettings() {
        return userNotificationSettingsService.getCurrentUserNotificationSettings();
    }

    @PutMapping("/me/notification-settings")
    public NotificationSettingsResponse updateNotificationSettings(
        @Valid @RequestBody UpdateNotificationSettingsRequest request
    ) {
        return userNotificationSettingsService.updateCurrentUserNotificationSettings(request);
    }
}
