import { apiFetch, apiFetchBlob } from "../../shared/api/http";
import type {
  ReportDownloadResponse,
  ReportFormat,
  ReportJobResponse,
  ReportType,
} from "../../shared/api/types";

export interface CreateReportRequest {
  year: number;
  month: number;
  reportType: ReportType;
  reportFormat: ReportFormat;
}

export function getReports() {
  return apiFetch<ReportJobResponse[]>("/api/reports");
}

export function getReport(id: number) {
  return apiFetch<ReportJobResponse>(`/api/reports/${id}`);
}

export function createReport(request: CreateReportRequest) {
  return apiFetch<ReportJobResponse>("/api/reports", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
}

export function getReportDownload(id: number) {
  return apiFetch<ReportDownloadResponse>(`/api/reports/${id}/download`);
}

export function downloadReportFile(id: number) {
  return apiFetchBlob(`/api/reports/${id}/file`, {
    headers: {
      Accept: "*/*",
    },
  });
}
