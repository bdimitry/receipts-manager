import type { CurrencyCode, PurchaseResponse } from "../../../shared/api/types";
import { getCategoryLabel } from "../../../shared/lib/domain";
import type { TranslationKey } from "../../../shared/i18n/translations";

const MAX_VISIBLE_SEGMENTS = 5;
const MIN_SEGMENT_SHARE = 0.06;

type Translate = (key: TranslationKey) => string;

export interface SpendingSegment {
  key: string;
  label: string;
  value: number;
  percentage: number;
}

export interface CurrencySummary {
  currency: CurrencyCode;
  total: number;
}

export function buildSpendingSegments(purchases: PurchaseResponse[], t: Translate): SpendingSegment[] {
  const categoryMap = purchases.reduce<Map<string, number>>((accumulator, purchase) => {
    const key = purchase.category.trim().toUpperCase();
    accumulator.set(key, (accumulator.get(key) ?? 0) + purchase.amount);
    return accumulator;
  }, new Map());

  const total = purchases.reduce((sum, purchase) => sum + purchase.amount, 0);
  if (!total) {
    return [];
  }

  const sortedSegments = Array.from(categoryMap.entries())
    .map(([key, value]) => ({
      key,
      label: getCategoryLabel(key, t),
      value,
      percentage: value / total,
    }))
    .sort((left, right) => right.value - left.value);

  const visible: SpendingSegment[] = [];
  let otherTotal = 0;

  sortedSegments.forEach((segment, index) => {
    const shouldAggregate =
      index >= MAX_VISIBLE_SEGMENTS - 1 || segment.percentage < MIN_SEGMENT_SHARE;

    if (shouldAggregate) {
      otherTotal += segment.value;
      return;
    }

    visible.push(segment);
  });

  if (otherTotal > 0) {
    visible.push({
      key: "OTHER",
      label: t("categoryOther"),
      value: otherTotal,
      percentage: otherTotal / total,
    });
  }

  return visible;
}

export function buildCurrencySummaries(purchases: PurchaseResponse[]): CurrencySummary[] {
  const totals = purchases.reduce<Map<CurrencyCode, number>>((accumulator, purchase) => {
    accumulator.set(purchase.currency, (accumulator.get(purchase.currency) ?? 0) + purchase.amount);
    return accumulator;
  }, new Map());

  return Array.from(totals.entries())
    .map(([currency, total]) => ({ currency, total }))
    .sort((left, right) => right.total - left.total);
}
