import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import {
  createTelegramConnectSession,
  getCurrentUser,
  getNotificationSettings,
  getTelegramConnectionStatus,
  updateNotificationSettings,
} from "../../user/api";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { getNotificationChannelLabel } from "../../../shared/lib/domain";
import { formatDateTime } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import type { LanguageCode } from "../../../shared/api/types";

const profileCopy: Record<
  LanguageCode,
  {
    emailDeliveryStatus: string;
    emailReady: string;
    telegramConnectionStatus: string;
    telegramConnected: string;
    telegramNotConnected: string;
    telegramConnectedAt: string;
    connectTelegram: string;
    openTelegramBot: string;
    telegramPendingHint: string;
    telegramConnectReady: string;
  }
> = {
  en: {
    emailDeliveryStatus: "Email delivery status",
    emailReady: "Finished reports are automatically sent to the email used during registration.",
    telegramConnectionStatus: "Telegram connection",
    telegramConnected: "Telegram connected",
    telegramNotConnected: "Telegram not connected yet",
    telegramConnectedAt: "Connected at:",
    connectTelegram: "Connect Telegram",
    openTelegramBot: "Open bot",
    telegramPendingHint: "This connect link stays active until",
    telegramConnectReady: "Create a connect link and confirm Start in the bot to pair Telegram delivery.",
  },
  ru: {
    emailDeliveryStatus: "Статус email-доставки",
    emailReady: "Готовые отчеты автоматически отправляются на email из регистрации.",
    telegramConnectionStatus: "Статус Telegram",
    telegramConnected: "Telegram подключен",
    telegramNotConnected: "Telegram еще не подключен",
    telegramConnectedAt: "Подключено:",
    connectTelegram: "Подключить Telegram",
    openTelegramBot: "Открыть бота",
    telegramPendingHint: "Ссылка для подключения активна до",
    telegramConnectReady: "Создайте ссылку и подтвердите Start в боте, чтобы подключить доставку в Telegram.",
  },
  uk: {
    emailDeliveryStatus: "Статус email-доставки",
    emailReady: "Готові звіти автоматично надсилаються на email з реєстрації.",
    telegramConnectionStatus: "Статус Telegram",
    telegramConnected: "Telegram підключено",
    telegramNotConnected: "Telegram ще не підключено",
    telegramConnectedAt: "Підключено:",
    connectTelegram: "Підключити Telegram",
    openTelegramBot: "Відкрити бота",
    telegramPendingHint: "Посилання для підключення активне до",
    telegramConnectReady: "Створіть посилання й підтвердіть Start у боті, щоб підключити доставку в Telegram.",
  },
};

const schema = z.object({
  preferredNotificationChannel: z.enum(["EMAIL", "TELEGRAM"]),
});

type NotificationFormValues = z.infer<typeof schema>;

export function ProfilePage() {
  const { t, language } = useI18n();
  const copy = profileCopy[language];
  const queryClient = useQueryClient();
  const currentUserQuery = useQuery({
    queryKey: ["current-user"],
    queryFn: getCurrentUser,
  });
  const notificationQuery = useQuery({
    queryKey: ["notification-settings"],
    queryFn: getNotificationSettings,
  });
  const telegramConnectionQuery = useQuery({
    queryKey: ["telegram-connection-status"],
    queryFn: getTelegramConnectionStatus,
    refetchInterval: (query) => (query.state.data?.connected ? false : 3000),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<NotificationFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      preferredNotificationChannel: "EMAIL",
    },
  });

  useEffect(() => {
    if (notificationQuery.data) {
      reset({
        preferredNotificationChannel: notificationQuery.data.preferredNotificationChannel,
      });
    }
  }, [notificationQuery.data, reset]);

  const mutation = useMutation({
    mutationFn: updateNotificationSettings,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ["notification-settings"] }),
        queryClient.invalidateQueries({ queryKey: ["telegram-connection-status"] }),
      ]),
  });

  const connectMutation = useMutation({
    mutationFn: createTelegramConnectSession,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["telegram-connection-status"] });
    },
  });

  if (currentUserQuery.isLoading || notificationQuery.isLoading || telegramConnectionQuery.isLoading) {
    return <LoadingState label={t("loading")} />;
  }

  if (currentUserQuery.isError || notificationQuery.isError || telegramConnectionQuery.isError) {
    return (
      <ErrorState
        title={t("errorTitle")}
        message={
          currentUserQuery.error?.message ??
          notificationQuery.error?.message ??
          telegramConnectionQuery.error?.message ??
          t("errorTitle")
        }
      />
    );
  }

  const currentUser = currentUserQuery.data;
  const notificationSettings = notificationQuery.data;
  const telegramConnection = telegramConnectionQuery.data;

  if (!currentUser || !notificationSettings || !telegramConnection) {
    return <ErrorState title={t("errorTitle")} message={t("noData")} />;
  }

  return (
    <div className="page-grid page-grid--two-columns">
      <PageIntro title={t("profile")} subtitle={t("profileSubtitle")} />
      <Card>
        <h2>{t("profileInfo")}</h2>
        <dl className="detail-grid">
          <div>
            <dt>{t("email")}</dt>
            <dd>{currentUser.email}</dd>
          </div>
          <div>
            <dt>{t("created")}</dt>
            <dd>{formatDateTime(currentUser.createdAt, language)}</dd>
          </div>
        </dl>
      </Card>
      <Card>
        <h2>{t("notificationSettings")}</h2>
        <div className="detail-grid detail-grid--single-column">
          <div>
            <dt>{copy.emailDeliveryStatus}</dt>
            <dd>{notificationSettings.email}</dd>
            <p className="field-hint">{copy.emailReady}</p>
          </div>
          <div>
            <dt>{copy.telegramConnectionStatus}</dt>
            <dd>{telegramConnection.connected ? copy.telegramConnected : copy.telegramNotConnected}</dd>
            <p className="field-hint">
              {telegramConnection.connected
                ? telegramConnection.connectedAt
                  ? `${copy.telegramConnectedAt} ${formatDateTime(telegramConnection.connectedAt, language)}`
                  : copy.telegramConnected
                : copy.telegramConnectReady}
            </p>
            {!telegramConnection.connected ? (
              <div className="stack-sm">
                <Button
                  disabled={connectMutation.isPending}
                  onClick={() => connectMutation.mutate()}
                  variant="secondary"
                >
                  {copy.connectTelegram}
                </Button>
                {telegramConnection.pendingDeepLink ? (
                  <a
                    className="inline-link"
                    href={telegramConnection.pendingDeepLink}
                    rel="noreferrer"
                    target="_blank"
                  >
                    {copy.openTelegramBot}
                  </a>
                ) : null}
                {telegramConnection.pendingExpiresAt ? (
                  <p className="field-hint">
                    {copy.telegramPendingHint}{" "}
                    {formatDateTime(telegramConnection.pendingExpiresAt, language)}
                  </p>
                ) : null}
                {connectMutation.isError ? (
                  <p className="form-error">{connectMutation.error.message}</p>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
        <form
          className="form-grid"
          onSubmit={handleSubmit((values) =>
            mutation.mutate({
              preferredNotificationChannel: values.preferredNotificationChannel,
              telegramChatId: null,
            }))
          }
        >
          <label className="field">
            <span>{t("preferredChannel")}</span>
            <select {...register("preferredNotificationChannel")}>
              <option value="EMAIL">{getNotificationChannelLabel("EMAIL", t)}</option>
              <option value="TELEGRAM">{getNotificationChannelLabel("TELEGRAM", t)}</option>
            </select>
          </label>
          {errors.preferredNotificationChannel ? (
            <small>{errors.preferredNotificationChannel.message}</small>
          ) : null}
          <p className="field-hint">
            {notificationSettings.preferredNotificationChannel === "TELEGRAM" && telegramConnection.connected
              ? `${t("notificationChannelTelegram")} -> ${copy.telegramConnected}`
              : `${t("notificationChannelEmail")} -> ${notificationSettings.email}`}
          </p>
          {mutation.isError ? <p className="form-error">{mutation.error.message}</p> : null}
          {mutation.isSuccess ? <p className="form-success">{t("profileSavedSuccess")}</p> : null}
          <Button disabled={mutation.isPending} type="submit">
            {t("save")}
          </Button>
        </form>
        <p className="field-hint">{t("notificationHint")}</p>
      </Card>
    </div>
  );
}
