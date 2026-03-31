import { useEffect, useId, useRef, useState } from "react";
import type { LanguageCode } from "../api/types";
import { useI18n } from "../i18n/I18nContext";
import flagEn from "../assets/flags/en.svg";
import flagRu from "../assets/flags/ru.svg";
import flagUk from "../assets/flags/uk.svg";

const languageFlags: Record<LanguageCode, string> = {
  ru: flagRu,
  uk: flagUk,
  en: flagEn,
};

export function LanguageDropdown() {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const panelId = useId();
  const { language, languages, setLanguage, t } = useI18n();

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const handleOutsideClick = (event: MouseEvent) => {
      if (
        containerRef.current &&
        event.target instanceof Node &&
        !containerRef.current.contains(event.target)
      ) {
        setOpen(false);
      }
    };

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };

    document.addEventListener("mousedown", handleOutsideClick);
    document.addEventListener("keydown", handleEscape);
    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
      document.removeEventListener("keydown", handleEscape);
    };
  }, [open]);

  return (
    <div className="language-dropdown" ref={containerRef}>
      <button
        type="button"
        aria-controls={panelId}
        aria-expanded={open}
        aria-label={t("language")}
        className="language-dropdown__trigger"
        data-testid="language-dropdown-trigger"
        onClick={() => setOpen((current) => !current)}
      >
        <img
          alt=""
          aria-hidden="true"
          className="language-dropdown__flag"
          data-testid="language-flag-current"
          src={languageFlags[language]}
        />
        <span>{language.toUpperCase()}</span>
      </button>
      {open ? (
        <div className="language-dropdown__panel" id={panelId}>
          {languages.map((item) => (
            <button
              key={item.code}
              className={`language-dropdown__option ${language === item.code ? "language-dropdown__option--active" : ""}`}
              type="button"
              onClick={() => {
                setLanguage(item.code);
                setOpen(false);
              }}
            >
              <img
                alt=""
                aria-hidden="true"
                className="language-dropdown__flag"
                data-testid={`language-flag-${item.code}`}
                src={languageFlags[item.code]}
              />
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
