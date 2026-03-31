import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { getPurchases } from "../../purchases/api";
import { getReceipts } from "../../receipts/api";
import { getReports } from "../../reports/api";
import type { CurrencyCode } from "../../../shared/api/types";
import { useI18n } from "../../../shared/i18n/I18nContext";
import {
  getCurrencyLabel,
  getOcrStatusLabel,
  getReportFormatLabel,
  getReportTypeLabel,
} from "../../../shared/lib/domain";
import { formatCurrency, formatDateTime, formatMonthLabel } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { SpendingDonutChart } from "../components/SpendingDonutChart";
import { buildCurrencySummaries, buildSpendingSegments } from "../lib/chart";

function getCurrentPeriod() {
  const now = new Date();
  return {
    year: now.getFullYear(),
    month: now.getMonth() + 1,
  };
}

export function DashboardPage() {
  const { year, month } = getCurrentPeriod();
  const { t, language } = useI18n();
  const purchasesQuery = useQuery({
    queryKey: ["dashboard-purchases", year, month],
    queryFn: () => getPurchases({ year, month }),
  });
  const receiptsQuery = useQuery({
    queryKey: ["dashboard-receipts"],
    queryFn: getReceipts,
    refetchInterval: (query) =>
      query.state.data?.some((receipt) => receipt.ocrStatus === "NEW" || receipt.ocrStatus === "PROCESSING")
        ? 4_000
        : false,
  });
  const reportsQuery = useQuery({
    queryKey: ["dashboard-reports"],
    queryFn: getReports,
    refetchInterval: (query) =>
      query.state.data?.some((report) => report.status === "NEW" || report.status === "PROCESSING")
        ? 4_000
        : false,
  });
  const purchases = purchasesQuery.data ?? [];
  const receipts = receiptsQuery.data ?? [];
  const reports = reportsQuery.data ?? [];
  const currencySummaries = useMemo(() => buildCurrencySummaries(purchases), [purchases]);
  const [selectedCurrency, setSelectedCurrency] = useState<CurrencyCode | null>(currencySummaries[0]?.currency ?? null);

  useEffect(() => {
    if (!currencySummaries.length) {
      setSelectedCurrency(null);
      return;
    }

    if (!selectedCurrency || !currencySummaries.some((summary) => summary.currency === selectedCurrency)) {
      setSelectedCurrency(currencySummaries[0].currency);
    }
  }, [currencySummaries, selectedCurrency]);

  const activeCurrency = selectedCurrency ?? currencySummaries[0]?.currency ?? null;
  const activePurchases = activeCurrency
    ? purchases.filter((purchase) => purchase.currency === activeCurrency)
    : purchases;
  const selectedTotal = activeCurrency
    ? currencySummaries.find((summary) => summary.currency === activeCurrency)?.total ?? 0
    : 0;
  const chartData = buildSpendingSegments(activePurchases, t);

  if (purchasesQuery.isLoading || receiptsQuery.isLoading || reportsQuery.isLoading) {
    return <LoadingState label={t("loading")} />;
  }

  if (purchasesQuery.isError || receiptsQuery.isError || reportsQuery.isError) {
    return (
      <ErrorState
        title={t("errorTitle")}
        message={
          purchasesQuery.error?.message ??
          receiptsQuery.error?.message ??
          reportsQuery.error?.message ??
          t("errorTitle")
        }
        onRetry={() => {
          purchasesQuery.refetch();
          receiptsQuery.refetch();
          reportsQuery.refetch();
        }}
      />
    );
  }

  const activeOcrCount = receipts.filter(
    (receipt) => receipt.ocrStatus === "NEW" || receipt.ocrStatus === "PROCESSING",
  ).length;
  const readyReportsCount = reports.filter((report) => report.status === "DONE").length;

  const activity = [
    ...receipts.map((receipt) => ({
      id: `receipt-${receipt.id}`,
      time: receipt.ocrProcessedAt ?? receipt.uploadedAt,
      title: `${getOcrStatusLabel(receipt.ocrStatus, t)}: ${receipt.originalFileName}`,
    })),
    ...reports.map((report) => ({
      id: `report-${report.id}`,
      time: report.updatedAt,
      title: `${report.status === "DONE" ? t("reportReady") : report.status === "FAILED" ? t("reportFailed") : t("reportPending")}: ${getReportTypeLabel(report.reportType, t)} ${getReportFormatLabel(report.reportFormat)}`,
    })),
  ]
    .sort((left, right) => new Date(right.time).getTime() - new Date(left.time).getTime())
    .slice(0, 5);

  return (
    <div className="dashboard-grid">
      <PageIntro
        title={formatMonthLabel(year, month, language)}
        subtitle={t("dashboardSubtitle")}
        action={
          <Link to="/reports">
            <Button>{t("dashboardHeroButton")}</Button>
          </Link>
        }
      />
      <Card className="hero-card">
        <div className="hero-card__content">
          <p className="hero-card__eyebrow">{t("totalForMonth")}</p>
          <h2>
            {activeCurrency ? formatCurrency(selectedTotal, language, activeCurrency) : t("noData")}
          </h2>
          <p>{currencySummaries.length > 1 ? t("mixedCurrencyWarning") : t("recentReceiptsAndReports")}</p>
          {currencySummaries.length ? (
            <div className="currency-summary-grid">
              {currencySummaries.map((summary) => (
                <button
                  className={`currency-summary-pill ${summary.currency === activeCurrency ? "currency-summary-pill--active" : ""}`}
                  key={summary.currency}
                  onClick={() => setSelectedCurrency(summary.currency)}
                  type="button"
                >
                  <span>{getCurrencyLabel(summary.currency, t)}</span>
                  <strong>{formatCurrency(summary.total, language, summary.currency)}</strong>
                </button>
              ))}
            </div>
          ) : null}
          <div className="hero-card__metrics">
            <div className="metric-pill">
              <span>{t("activeOcr")}</span>
              <strong>{activeOcrCount}</strong>
            </div>
            <div className="metric-pill">
              <span>{t("readyReports")}</span>
              <strong>{readyReportsCount}</strong>
            </div>
            <div className="metric-pill">
              <span>{t("categoriesTracked")}</span>
              <strong>{chartData.length}</strong>
            </div>
          </div>
        </div>
        <div className="hero-card__actions">
          <Link to="/purchases">
            <Button variant="secondary">{t("newPurchase")}</Button>
          </Link>
          <Link to="/receipts">
            <Button variant="ghost">{t("uploadReceipt")}</Button>
          </Link>
        </div>
      </Card>
      <Card className="section-card section-card--wide">
        <div className="section-card__header">
          <div>
            <h2>{t("spendingByCategory")}</h2>
            <p>
              {activeCurrency
                ? `${t("thisMonth")} / ${getCurrencyLabel(activeCurrency, t)}`
                : t("thisMonth")}
            </p>
          </div>
        </div>
        {chartData.length ? (
          <SpendingDonutChart data={chartData} total={selectedTotal} currency={activeCurrency ?? "UAH"} />
        ) : (
          <EmptyState message={t("emptyPurchases")} />
        )}
      </Card>
      <Card className="section-card">
        <div className="section-card__header">
          <div>
            <h2>{t("recentActivity")}</h2>
            <p>{t("recentReceiptsAndReports")}</p>
          </div>
        </div>
        {activity.length ? (
          <div className="activity-list">
            {activity.map((item) => (
              <article className="activity-list__item" key={item.id}>
                <strong>{item.title}</strong>
                <span>{formatDateTime(item.time, language)}</span>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState message={t("noData")} />
        )}
      </Card>
      <Card className="section-card">
        <div className="section-card__header">
          <div>
            <h2>{t("quickActions")}</h2>
            <p>{t("dashboardSubtitle")}</p>
          </div>
        </div>
        <div className="quick-actions">
          <Link className="quick-actions__item" to="/purchases">
            <strong>{t("purchases")}</strong>
            <span>{t("purchasesSubtitle")}</span>
          </Link>
          <Link className="quick-actions__item" to="/receipts">
            <strong>{t("receipts")}</strong>
            <span>{t("receiptsSubtitle")}</span>
          </Link>
          <Link className="quick-actions__item" to="/reports">
            <strong>{t("reportCenter")}</strong>
            <span>{t("reportCenterSubtitle")}</span>
          </Link>
        </div>
      </Card>
    </div>
  );
}

