import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { LanguageCode } from "../api/types";
import { readStorage, writeStorage } from "../lib/storage";
import {
  languages,
  translationDictionary,
  type TranslationKey,
} from "./translations";

const STORAGE_KEY = "hb.language";

interface I18nContextValue {
  language: LanguageCode;
  languages: typeof languages;
  setLanguage: (language: LanguageCode) => void;
  t: (key: TranslationKey) => string;
}

const I18nContext = createContext<I18nContextValue | undefined>(undefined);

function detectDefaultLanguage(): LanguageCode {
  const browserLanguage = window.navigator.language.toLowerCase();
  if (browserLanguage.startsWith("uk")) {
    return "uk";
  }

  if (browserLanguage.startsWith("en")) {
    return "en";
  }

  return "ru";
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<LanguageCode>(() =>
    readStorage<LanguageCode>(STORAGE_KEY, detectDefaultLanguage()),
  );

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const setLanguage = (nextLanguage: LanguageCode) => {
    setLanguageState(nextLanguage);
    writeStorage(STORAGE_KEY, nextLanguage);
    document.documentElement.lang = nextLanguage;
  };

  const value = useMemo<I18nContextValue>(
    () => ({
      language,
      languages,
      setLanguage,
      t: (key) => {
        const localizedDictionary = translationDictionary[language] as Partial<Record<TranslationKey, string>>;
        return localizedDictionary[key] ?? translationDictionary.en[key] ?? key;
      },
    }),
    [language],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error("useI18n must be used within I18nProvider");
  }

  return context;
}
