import type { CurrencyCode } from "../api/types";

export function formatCurrency(amount: number, language: string, currency: CurrencyCode) {
  return new Intl.NumberFormat(language, {
    style: "currency",
    currency,
    maximumFractionDigits: 2,
  }).format(amount);
}

export function formatDate(value: string, language: string) {
  return new Intl.DateTimeFormat(language, {
    year: "numeric",
    month: "short",
    day: "numeric",
  }).format(new Date(value));
}

export function formatDateTime(value: string, language: string) {
  return new Intl.DateTimeFormat(language, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

export function formatMonthLabel(year: number, month: number, language: string) {
  return new Intl.DateTimeFormat(language, {
    year: "numeric",
    month: "long",
  }).format(new Date(year, month - 1, 1));
}

export function formatPercent(value: number, language: string) {
  return new Intl.NumberFormat(language, {
    style: "percent",
    maximumFractionDigits: value < 0.1 ? 1 : 0,
  }).format(value);
}
