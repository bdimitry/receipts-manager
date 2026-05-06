import { apiFetch } from "../../shared/api/http";
import type {
  CurrencyCode,
  ReceiptCountryHint,
  ReceiptCorrectionRequest,
  ReceiptCorrectionResponse,
  ReceiptOcrResponse,
  ReceiptResponse,
} from "../../shared/api/types";

export function getReceipts() {
  return apiFetch<ReceiptResponse[]>("/api/receipts");
}

export function getReceipt(id: number) {
  return apiFetch<ReceiptResponse>(`/api/receipts/${id}`);
}

export function getReceiptOcr(id: number) {
  return apiFetch<ReceiptOcrResponse>(`/api/receipts/${id}/ocr`);
}

export function submitReceiptCorrection(id: number, request: ReceiptCorrectionRequest) {
  return apiFetch<ReceiptCorrectionResponse>(`/api/receipts/${id}/correction`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

export async function uploadReceipt(
  file: File,
  currency: CurrencyCode,
  purchaseId?: number,
  receiptCountryHint?: ReceiptCountryHint,
) {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("currency", currency);
  if (purchaseId) {
    formData.append("purchaseId", String(purchaseId));
  }
  if (receiptCountryHint) {
    formData.append("receiptCountryHint", receiptCountryHint);
  }

  return apiFetch<ReceiptResponse>("/api/receipts/upload", {
    method: "POST",
    body: formData,
  });
}
