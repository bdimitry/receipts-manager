import type {
  CurrencyCode,
  NotificationChannel,
  ReceiptOcrStatus,
  ReportFormat,
  ReportJobStatus,
  ReportType,
} from "../api/types";
import type { TranslationKey } from "../i18n/translations";

type Translate = (key: TranslationKey) => string;

const reportTypeKeyMap: Record<ReportType, TranslationKey> = {
  MONTHLY_SPENDING: "reportTypeMonthlySpendingLabel",
  CATEGORY_SUMMARY: "reportTypeCategorySummaryLabel",
  STORE_SUMMARY: "reportTypeStoreSummaryLabel",
};

const reportStatusKeyMap: Record<ReportJobStatus, TranslationKey> = {
  NEW: "reportPending",
  PROCESSING: "reportPending",
  DONE: "reportReady",
  FAILED: "reportFailed",
};

const ocrStatusKeyMap: Record<ReceiptOcrStatus, TranslationKey> = {
  NEW: "ocrPending",
  PROCESSING: "ocrPending",
  DONE: "ocrDone",
  FAILED: "ocrFailed",
};

const categoryKeyMap: Record<string, TranslationKey> = {
  FOOD: "categoryFood",
  UTILITIES: "categoryUtilities",
  TRANSPORT: "categoryTransport",
  HEALTH: "categoryHealth",
  ENTERTAINMENT: "categoryEntertainment",
  SHOPPING: "categoryShopping",
  HOUSING: "categoryHousing",
  EDUCATION: "categoryEducation",
  FAMILY: "categoryFamily",
  TRAVEL: "categoryTravel",
  SUBSCRIPTIONS: "categorySubscriptions",
  BILLS: "categoryBills",
  OTHER: "categoryOther",
};

const notificationChannelKeyMap: Record<NotificationChannel, TranslationKey> = {
  EMAIL: "notificationChannelEmail",
  TELEGRAM: "notificationChannelTelegram",
};

const currencyKeyMap: Record<CurrencyCode, TranslationKey> = {
  USD: "currencyUsd",
  EUR: "currencyEur",
  UAH: "currencyUah",
  RUB: "currencyRub",
};

function titleCase(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((chunk) => chunk.charAt(0).toUpperCase() + chunk.slice(1))
    .join(" ");
}

export function getReportTypeLabel(reportType: ReportType, t: Translate) {
  return t(reportTypeKeyMap[reportType]);
}

export function getReportFormatLabel(reportFormat: ReportFormat) {
  return reportFormat;
}

export function getReportStatusLabel(status: ReportJobStatus, t: Translate) {
  return t(reportStatusKeyMap[status]);
}

export function getOcrStatusLabel(status: ReceiptOcrStatus, t: Translate) {
  return t(ocrStatusKeyMap[status]);
}

export function getNotificationChannelLabel(channel: NotificationChannel, t: Translate) {
  return t(notificationChannelKeyMap[channel]);
}

export function getCurrencyLabel(currency: CurrencyCode, t: Translate) {
  return t(currencyKeyMap[currency]);
}

export function getCategoryLabel(category: string, t: Translate) {
  const key = categoryKeyMap[category.trim().toUpperCase()];
  return key ? t(key) : titleCase(category);
}
