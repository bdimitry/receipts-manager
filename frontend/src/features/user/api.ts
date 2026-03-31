import { apiFetch } from "../../shared/api/http";
import type {
  CurrentUserResponse,
  NotificationChannel,
  NotificationSettingsResponse,
} from "../../shared/api/types";

export function getCurrentUser() {
  return apiFetch<CurrentUserResponse>("/api/users/me");
}

export function getNotificationSettings() {
  return apiFetch<NotificationSettingsResponse>("/api/users/me/notification-settings");
}

export function updateNotificationSettings(request: {
  preferredNotificationChannel: NotificationChannel;
  telegramChatId?: string | null;
}) {
  return apiFetch<NotificationSettingsResponse>("/api/users/me/notification-settings", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}
