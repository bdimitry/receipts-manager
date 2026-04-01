import { apiFetch } from "../../shared/api/http";
import type {
  CurrentUserResponse,
  NotificationChannel,
  NotificationSettingsResponse,
  TelegramConnectSessionResponse,
  TelegramConnectionStatusResponse,
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

export function createTelegramConnectSession() {
  return apiFetch<TelegramConnectSessionResponse>("/api/users/me/telegram/connect-session", {
    method: "POST",
  });
}

export function getTelegramConnectionStatus() {
  return apiFetch<TelegramConnectionStatusResponse>("/api/users/me/telegram/connection");
}
