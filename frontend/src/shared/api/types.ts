export type ThemeMode = "light" | "dark";
export type LanguageCode = "ru" | "uk" | "en";

export type NotificationChannel = "EMAIL" | "TELEGRAM";
export type ReceiptOcrStatus = "NEW" | "PROCESSING" | "DONE" | "FAILED";
export type ReportJobStatus = "NEW" | "PROCESSING" | "DONE" | "FAILED";
export type ReportType = "MONTHLY_SPENDING" | "CATEGORY_SUMMARY" | "STORE_SUMMARY";
export type ReportFormat = "CSV" | "PDF" | "XLSX";
export type CurrencyCode = "USD" | "EUR" | "UAH" | "RUB";

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
}

export interface CurrentUserResponse {
  id: number;
  email: string;
  createdAt: string;
  role: "USER" | "ADMIN";
  admin: boolean;
}

export interface AdminUserResponse {
  id: number;
  email: string;
  role: "USER" | "ADMIN";
  authProvider: "PASSWORD" | "GOOGLE";
  createdAt: string;
}

export interface AdminOverviewResponse {
  usersCount: number;
  purchasesCount: number;
  receiptsCount: number;
  reportJobsCount: number;
  recentUsers: AdminUserResponse[];
}

export interface NotificationSettingsResponse {
  email: string;
  telegramChatId: string | null;
  preferredNotificationChannel: NotificationChannel;
}

export interface PurchaseResponse {
  id: number;
  title: string;
  category: string;
  amount: number;
  currency: CurrencyCode;
  purchaseDate: string;
  storeName: string | null;
  comment: string | null;
  createdAt: string;
  items: PurchaseItemResponse[];
}

export interface PurchaseItemResponse {
  id: number | null;
  lineIndex: number;
  title: string;
  quantity: number | null;
  unit: string | null;
  unitPrice: number | null;
  lineTotal: number | null;
}

export interface ReceiptResponse {
  id: number;
  purchaseId: number | null;
  originalFileName: string;
  contentType: string;
  fileSize: number;
  currency: CurrencyCode;
  s3Key: string;
  uploadedAt: string;
  ocrStatus: ReceiptOcrStatus;
  parsedStoreName: string | null;
  parsedTotalAmount: number | null;
  parsedPurchaseDate: string | null;
  parsedLineItemCount: number;
  ocrErrorMessage: string | null;
  ocrProcessedAt: string | null;
}

export interface ReceiptLineItemResponse {
  id: number | null;
  lineIndex: number;
  title: string;
  quantity: number | null;
  unit: string | null;
  unitPrice: number | null;
  lineTotal: number | null;
  rawFragment: string | null;
}

export interface ReceiptOcrResponse {
  receiptId: number;
  currency: CurrencyCode;
  ocrStatus: ReceiptOcrStatus;
  rawOcrText: string | null;
  parsedStoreName: string | null;
  parsedTotalAmount: number | null;
  parsedPurchaseDate: string | null;
  lineItems: ReceiptLineItemResponse[];
  ocrErrorMessage: string | null;
  ocrProcessedAt: string | null;
}

export interface ReportJobResponse {
  id: number;
  year: number;
  month: number;
  reportType: ReportType;
  reportFormat: ReportFormat;
  status: ReportJobStatus;
  s3Key: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReportDownloadResponse {
  reportJobId: number;
  reportType: ReportType;
  reportFormat: ReportFormat;
  status: ReportJobStatus;
  fileName: string;
  contentType: string;
  downloadUrl: string;
  expiresAt: string;
}
