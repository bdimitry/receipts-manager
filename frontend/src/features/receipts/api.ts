import { apiFetch } from "../../shared/api/http";
import type { CurrencyCode, ReceiptOcrResponse, ReceiptResponse } from "../../shared/api/types";

export function getReceipts() {
  return apiFetch<ReceiptResponse[]>("/api/receipts");
}

export function getReceipt(id: number) {
  return apiFetch<ReceiptResponse>(`/api/receipts/${id}`);
}

export function getReceiptOcr(id: number) {
  return apiFetch<ReceiptOcrResponse>(`/api/receipts/${id}/ocr`);
}

export async function uploadReceipt(file: File, currency: CurrencyCode, purchaseId?: number) {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("currency", currency);
  if (purchaseId) {
    formData.append("purchaseId", String(purchaseId));
  }

  return apiFetch<ReceiptResponse>("/api/receipts/upload", {
    method: "POST",
    body: formData,
  });
}
