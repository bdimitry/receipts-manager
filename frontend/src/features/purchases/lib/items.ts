import type { PurchaseItemRequest } from "../api";

export interface PurchaseItemDraft {
  title: string;
  quantity?: number | string;
  unit?: string;
  unitPrice?: number | string;
  lineTotal?: number | string;
}

function toPositiveNumber(value: number | string | undefined) {
  if (value === undefined || value === "") {
    return undefined;
  }

  const numericValue = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(numericValue) || numericValue <= 0) {
    return undefined;
  }

  return numericValue;
}

export function hasMeaningfulPurchaseItem(item: PurchaseItemDraft) {
  return Boolean(
    item.title.trim() ||
      toPositiveNumber(item.quantity) ||
      item.unit?.trim() ||
      toPositiveNumber(item.unitPrice) ||
      toPositiveNumber(item.lineTotal),
  );
}

export function derivePurchaseItemTotal(item: PurchaseItemDraft) {
  const explicitLineTotal = toPositiveNumber(item.lineTotal);
  if (explicitLineTotal !== undefined) {
    return Number(explicitLineTotal.toFixed(2));
  }

  const quantity = toPositiveNumber(item.quantity);
  const unitPrice = toPositiveNumber(item.unitPrice);
  if (quantity !== undefined && unitPrice !== undefined) {
    return Number((quantity * unitPrice).toFixed(2));
  }

  return undefined;
}

export function derivePurchaseItemsTotal(items: PurchaseItemDraft[]) {
  return Number(
    items.reduce((total, item) => total + (derivePurchaseItemTotal(item) ?? 0), 0).toFixed(2),
  );
}

export function toPurchaseItemRequest(item: PurchaseItemDraft): PurchaseItemRequest {
  return {
    title: item.title.trim(),
    quantity: toPositiveNumber(item.quantity),
    unit: item.unit?.trim() || undefined,
    unitPrice: toPositiveNumber(item.unitPrice),
    lineTotal: derivePurchaseItemTotal(item),
  };
}
