import { Button } from "./Button";
import { useI18n } from "../i18n/I18nContext";

export function ErrorState({
  title,
  message,
  onRetry,
}: {
  title: string;
  message: string;
  onRetry?: () => void;
}) {
  const { t } = useI18n();

  return (
    <div className="error-state" role="alert">
      <h3>{title}</h3>
      <p>{message}</p>
      {onRetry ? <Button onClick={onRetry}>{t("retry")}</Button> : null}
    </div>
  );
}
