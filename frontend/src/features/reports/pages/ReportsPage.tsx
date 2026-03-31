import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { createReport, getReportDownload, getReports } from "../api";
import type { ReportFormat, ReportType } from "../../../shared/api/types";
import { useI18n } from "../../../shared/i18n/I18nContext";
import {
  getReportFormatLabel,
  getReportStatusLabel,
  getReportTypeLabel,
} from "../../../shared/lib/domain";
import { formatDateTime, formatMonthLabel } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { StatusBadge } from "../../../shared/ui/StatusBadge";

const schema = z.object({
  year: z.coerce.number().min(2000).max(9999),
  month: z.coerce.number().min(1).max(12),
  reportType: z.enum(["MONTHLY_SPENDING", "CATEGORY_SUMMARY", "STORE_SUMMARY"]),
  reportFormat: z.enum(["CSV", "PDF", "XLSX"]),
});

type ReportFormValues = z.infer<typeof schema>;
type ReportFormInput = z.input<typeof schema>;

function tone(status: string) {
  if (status === "DONE") {
    return "success";
  }

  if (status === "FAILED") {
    return "danger";
  }

  return "warning";
}

export function ReportsPage() {
  const { t, language } = useI18n();
  const queryClient = useQueryClient();
  const defaultValues = useMemo(() => {
    const now = new Date();
    return {
      year: now.getFullYear(),
      month: now.getMonth() + 1,
      reportType: "MONTHLY_SPENDING" as ReportType,
      reportFormat: "CSV" as ReportFormat,
    };
  }, []);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ReportFormInput, unknown, ReportFormValues>({
    resolver: zodResolver(schema),
    defaultValues,
  });
  const [selectedReportId, setSelectedReportId] = useState<number | null>(null);
  const reportsQuery = useQuery({
    queryKey: ["reports"],
    queryFn: getReports,
    refetchInterval: (query) =>
      query.state.data?.some((report) => report.status === "NEW" || report.status === "PROCESSING")
        ? 3_000
        : false,
  });

  const createMutation = useMutation({
    mutationFn: createReport,
    onSuccess: (report) => {
      queryClient.invalidateQueries({ queryKey: ["reports"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-reports"] });
      setSelectedReportId(report.id);
      reset(defaultValues);
    },
  });

  const downloadMutation = useMutation({
    mutationFn: getReportDownload,
    onSuccess: (download) => {
      window.open(download.downloadUrl, "_blank", "noopener,noreferrer");
    },
  });

  const selectedReport =
    reportsQuery.data?.find((report) => report.id === selectedReportId) ?? reportsQuery.data?.[0];

  return (
    <div className="page-grid page-grid--two-columns">
      <PageIntro title={t("reportCenter")} subtitle={t("reportCenterSubtitle")} />
      <Card>
        <h2>{t("createReportJob")}</h2>
        <form
          className="form-grid"
          onSubmit={handleSubmit((values) => createMutation.mutate(values))}
        >
          <label className="field">
            <span>{t("year")}</span>
            <input type="number" {...register("year")} />
            {errors.year ? <small>{errors.year.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("month")}</span>
            <input type="number" min="1" max="12" {...register("month")} />
            {errors.month ? <small>{errors.month.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("reportType")}</span>
            <select {...register("reportType")}>
              {(["MONTHLY_SPENDING", "CATEGORY_SUMMARY", "STORE_SUMMARY"] as const).map((value) => (
                <option key={value} value={value}>
                  {getReportTypeLabel(value, t)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{t("reportFormat")}</span>
            <select {...register("reportFormat")}>
              {(["CSV", "PDF", "XLSX"] as const).map((value) => (
                <option key={value} value={value}>
                  {getReportFormatLabel(value)}
                </option>
              ))}
            </select>
          </label>
          {createMutation.isError ? <p className="form-error">{createMutation.error.message}</p> : null}
          {createMutation.isSuccess ? <p className="form-success">{t("reportQueuedSuccess")}</p> : null}
          <Button disabled={createMutation.isPending} type="submit">
            {t("newReport")}
          </Button>
        </form>
      </Card>
      <Card>
        <h2>{t("details")}</h2>
        {selectedReport ? (
          <div className="report-detail-panel">
            <StatusBadge tone={tone(selectedReport.status)}>
              {getReportStatusLabel(selectedReport.status, t)}
            </StatusBadge>
            <h3>{getReportTypeLabel(selectedReport.reportType, t)}</h3>
            <p>
              {formatMonthLabel(selectedReport.year, selectedReport.month, language)} /{" "}
              {getReportFormatLabel(selectedReport.reportFormat)}
            </p>
            <dl className="detail-grid">
              <div>
                <dt>{t("s3Key")}</dt>
                <dd>{selectedReport.s3Key ?? "-"}</dd>
              </div>
              <div>
                <dt>{t("errorLabel")}</dt>
                <dd>{selectedReport.errorMessage ?? "-"}</dd>
              </div>
              <div>
                <dt>{t("updated")}</dt>
                <dd>{formatDateTime(selectedReport.updatedAt, language)}</dd>
              </div>
            </dl>
            <p className="field-hint">
              {selectedReport.status === "DONE"
                ? t("downloadReady")
                : selectedReport.status === "FAILED"
                  ? t("downloadFailed")
                  : t("downloadPending")}
            </p>
            <Button
              disabled={selectedReport.status !== "DONE" || downloadMutation.isPending}
              onClick={() => downloadMutation.mutate(selectedReport.id)}
            >
              {t("download")}
            </Button>
          </div>
        ) : (
          <EmptyState message={t("emptyReports")} />
        )}
      </Card>
      <Card className="card--full">
        <div className="section-card__header">
          <div>
            <h2>{t("reports")}</h2>
            <p>{t("reportCenterSubtitle")}</p>
          </div>
        </div>
        {reportsQuery.isLoading ? <LoadingState label={t("loading")} /> : null}
        {reportsQuery.isError ? (
          <ErrorState title={t("errorTitle")} message={reportsQuery.error.message} onRetry={reportsQuery.refetch} />
        ) : null}
        {reportsQuery.data && reportsQuery.data.length === 0 ? (
          <EmptyState message={t("emptyReports")} />
        ) : null}
        {reportsQuery.data?.length ? (
          <div className="table-like-list">
            {reportsQuery.data.map((report) => (
              <button
                className={`list-row list-row--button ${selectedReport?.id === report.id ? "list-row--selected" : ""}`}
                key={report.id}
                onClick={() => setSelectedReportId(report.id)}
                type="button"
              >
                <div className="list-row__main">
                  <strong>{getReportTypeLabel(report.reportType, t)}</strong>
                  <span>
                    {formatMonthLabel(report.year, report.month, language)} /{" "}
                    {getReportFormatLabel(report.reportFormat)}
                  </span>
                </div>
                <div className="list-row__meta">
                  <StatusBadge tone={tone(report.status)}>
                    {getReportStatusLabel(report.status, t)}
                  </StatusBadge>
                </div>
              </button>
            ))}
          </div>
        ) : null}
      </Card>
    </div>
  );
}
