import { useI18n } from "../i18n/I18nContext";
import { useTheme } from "../theme/ThemeContext";

export function ThemeToggle() {
  const { t } = useI18n();
  const { theme, toggleTheme } = useTheme();

  return (
    <div className="topbar-control topbar-control--theme">
      <span className="topbar-control__label">{t("theme")}</span>
      <button
        type="button"
        aria-label={t("theme")}
        aria-pressed={theme === "dark"}
        className={`theme-toggle ${theme === "dark" ? "theme-toggle--dark" : ""}`}
        data-testid="theme-toggle"
        onClick={toggleTheme}
      >
        <span aria-hidden="true" className="theme-toggle__icon">
          {theme === "dark" ? "\uD83C\uDF19" : "\u2600\uFE0F"}
        </span>
        <span className="theme-toggle__thumb" />
      </button>
    </div>
  );
}
