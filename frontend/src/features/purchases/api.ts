import { apiFetch } from "../../shared/api/http";
import type { CurrencyCode, PurchaseResponse } from "../../shared/api/types";

export interface PurchaseItemRequest {
  title: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  lineTotal?: number;
}

export interface CreatePurchaseRequest {
  title: string;
  category: string;
  amount: number;
  currency: CurrencyCode;
  purchaseDate: string;
  storeName?: string;
  comment?: string;
  items?: PurchaseItemRequest[];
}

export interface PurchaseFilters {
  year?: number;
  month?: number;
  category?: string;
}

export function getPurchases(filters: PurchaseFilters = {}) {
  const params = new URLSearchParams();
  if (filters.year) {
    params.set("year", String(filters.year));
  }
  if (filters.month) {
    params.set("month", String(filters.month));
  }
  if (filters.category) {
    params.set("category", filters.category);
  }

  const query = params.toString();
  return apiFetch<PurchaseResponse[]>(`/api/purchases${query ? `?${query}` : ""}`);
}

export function createPurchase(request: CreatePurchaseRequest) {
  return apiFetch<PurchaseResponse>("/api/purchases", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

export function deletePurchase(id: number) {
  return apiFetch<void>(`/api/purchases/${id}`, {
    method: "DELETE",
  });
}
