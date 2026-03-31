import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import {
  getCurrentUser,
  getNotificationSettings,
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

const schema = z.object({
  preferredNotificationChannel: z.enum(["EMAIL", "TELEGRAM"]),
  telegramChatId: z.string().optional(),
});

type NotificationFormValues = z.infer<typeof schema>;

export function ProfilePage() {
  const { t, language } = useI18n();
  const queryClient = useQueryClient();
  const currentUserQuery = useQuery({
    queryKey: ["current-user"],
    queryFn: getCurrentUser,
  });
  const notificationQuery = useQuery({
    queryKey: ["notification-settings"],
    queryFn: getNotificationSettings,
  });

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<NotificationFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      preferredNotificationChannel: "EMAIL",
      telegramChatId: "",
    },
  });

  useEffect(() => {
    if (notificationQuery.data) {
      reset({
        preferredNotificationChannel: notificationQuery.data.preferredNotificationChannel,
        telegramChatId: notificationQuery.data.telegramChatId ?? "",
      });
    }
  }, [notificationQuery.data, reset]);

  const mutation = useMutation({
    mutationFn: updateNotificationSettings,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["notification-settings"] }),
  });

  if (currentUserQuery.isLoading || notificationQuery.isLoading) {
    return <LoadingState label={t("loading")} />;
  }

  if (currentUserQuery.isError || notificationQuery.isError) {
    return (
      <ErrorState
        title={t("errorTitle")}
        message={currentUserQuery.error?.message ?? notificationQuery.error?.message ?? t("errorTitle")}
      />
    );
  }

  const currentUser = currentUserQuery.data;
  const notificationSettings = notificationQuery.data;
  const preferredChannel = watch("preferredNotificationChannel");

  if (!currentUser || !notificationSettings) {
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
        <form
          className="form-grid"
          onSubmit={handleSubmit((values) =>
            mutation.mutate({
              preferredNotificationChannel: values.preferredNotificationChannel,
              telegramChatId: values.telegramChatId || null,
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
          <label className="field">
            <span>{t("telegramChatId")}</span>
            <input placeholder="555000111" {...register("telegramChatId")} />
            {errors.telegramChatId ? <small>{errors.telegramChatId.message}</small> : null}
          </label>
          <p className="field-hint">
            {preferredChannel === "TELEGRAM"
              ? `${t("notificationChannelTelegram")} -> ${watch("telegramChatId") || "555000111"}`
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
