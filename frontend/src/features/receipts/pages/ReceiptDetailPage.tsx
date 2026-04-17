import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { getReceipt, getReceiptOcr } from "../api";
import { useI18n } from "../../../shared/i18n/I18nContext";
import type { TranslationKey } from "../../../shared/i18n/translations";
import { getOcrStatusLabel } from "../../../shared/lib/domain";
import { formatCurrency, formatDate, formatDateTime } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { StatusBadge } from "../../../shared/ui/StatusBadge";

function tone(status: string) {
  if (status === "DONE") {
    return "success";
  }

  if (status === "FAILED") {
    return "danger";
  }

  return "warning";
}

function formatQuantity(value: number, language: string) {
  return new Intl.NumberFormat(language, {
    maximumFractionDigits: 3,
  }).format(value);
}

function getValidationWarningLabel(code: string, t: (key: TranslationKey) => string) {
  switch (code) {
    case "SUSPICIOUS_MERCHANT":
      return t("ocrWarningSuspiciousMerchant");
    case "SUSPICIOUS_TOTAL":
      return t("ocrWarningSuspiciousTotal");
    case "SUSPICIOUS_DATE":
      return t("ocrWarningSuspiciousDate");
    case "SUSPICIOUS_LINE_ITEMS":
      return t("ocrWarningSuspiciousLineItems");
    case "ITEM_TOTAL_MISMATCH":
      return t("ocrWarningItemTotalMismatch");
    case "PAYMENT_CONTENT_IN_ITEMS":
      return t("ocrWarningPaymentContentInItems");
    case "NOISY_ITEM_TITLES":
      return t("ocrWarningNoisyItemTitles");
    case "INCONSISTENT_ITEM_MATH":
      return t("ocrWarningInconsistentItemMath");
    default:
      return code;
  }
}

export function ReceiptDetailPage() {
  const { t, language } = useI18n();
  const params = useParams();
  const receiptId = Number(params.id);
  const receiptQuery = useQuery({
    queryKey: ["receipt", receiptId],
    queryFn: () => getReceipt(receiptId),
    refetchInterval: (query) =>
      query.state.data?.ocrStatus === "NEW" || query.state.data?.ocrStatus === "PROCESSING" ? 3_000 : false,
  });
  const ocrQuery = useQuery({
    queryKey: ["receipt-ocr", receiptId],
    queryFn: () => getReceiptOcr(receiptId),
    refetchInterval: (query) =>
      query.state.data?.ocrStatus === "NEW" || query.state.data?.ocrStatus === "PROCESSING" ? 3_000 : false,
  });

  if (receiptQuery.isLoading || ocrQuery.isLoading) {
    return <LoadingState label={t("loading")} />;
  }

  if (receiptQuery.isError || ocrQuery.isError) {
    return (
      <ErrorState
        title={t("errorTitle")}
        message={receiptQuery.error?.message ?? ocrQuery.error?.message ?? t("errorTitle")}
      />
    );
  }

  const receipt = receiptQuery.data;
  const ocr = ocrQuery.data;

  if (!receipt || !ocr) {
    return <ErrorState title={t("errorTitle")} message={t("noData")} />;
  }

  const displayCurrency = ocr.parsedCurrency ?? ocr.currency;
  const parseWarnings = ocr.parseWarnings ?? [];
  const weakParseQuality = ocr.weakParseQuality ?? false;

  return (
    <div className="page-grid">
      <PageIntro
        title={t("receiptDetail")}
        subtitle={receipt.originalFileName}
        action={
          <Link to="/receipts">
            <Button variant="ghost">{t("backToReceipts")}</Button>
          </Link>
        }
      />
      <Card>
        <div className="section-card__header">
          <div>
            <h2>{receipt.originalFileName}</h2>
            <p>{formatDateTime(receipt.uploadedAt, language)}</p>
          </div>
          <StatusBadge tone={tone(ocr.ocrStatus)}>{getOcrStatusLabel(ocr.ocrStatus, t)}</StatusBadge>
        </div>
        <dl className="detail-grid">
          <div>
            <dt>{t("currency")}</dt>
            <dd>{ocr.currency}</dd>
          </div>
          <div>
            <dt>{t("storeName")}</dt>
            <dd>{ocr.parsedStoreName ?? "-"}</dd>
          </div>
          <div>
            <dt>{t("amount")}</dt>
            <dd>{ocr.parsedTotalAmount ? formatCurrency(ocr.parsedTotalAmount, language, displayCurrency) : "-"}</dd>
          </div>
          <div>
            <dt>{t("purchaseDate")}</dt>
            <dd>{ocr.parsedPurchaseDate ? formatDate(ocr.parsedPurchaseDate, language) : "-"}</dd>
          </div>
          <div>
            <dt>{t("s3Key")}</dt>
            <dd>{receipt.s3Key}</dd>
          </div>
        </dl>
      </Card>
      <Card>
        <h2>{t("parsedFields")}</h2>
        <dl className="detail-grid">
          <div>
            <dt>{t("status")}</dt>
            <dd>{getOcrStatusLabel(ocr.ocrStatus, t)}</dd>
          </div>
          <div>
            <dt>{t("processed")}</dt>
            <dd>{ocr.ocrProcessedAt ? formatDateTime(ocr.ocrProcessedAt, language) : "-"}</dd>
          </div>
          <div>
            <dt>{t("errorLabel")}</dt>
            <dd>{ocr.ocrErrorMessage ?? "-"}</dd>
          </div>
          <div>
            <dt>{t("linkedPurchase")}</dt>
            <dd>{receipt.purchaseId ?? t("noLinkedPurchase")}</dd>
          </div>
        </dl>
      </Card>
      <Card>
        <h2>{t("parseQuality")}</h2>
        {parseWarnings.length ? (
          <>
            {weakParseQuality ? <p className="field-hint">{t("weakParseQualityHint")}</p> : null}
            <div className="table-like-list">
              {parseWarnings.map((warningCode) => (
                <article className="list-row" key={warningCode}>
                  <div className="list-row__main">
                    <strong>{getValidationWarningLabel(warningCode, t)}</strong>
                    <span>{warningCode}</span>
                  </div>
                </article>
              ))}
            </div>
          </>
        ) : (
          <EmptyState message={t("noValidationWarnings")} />
        )}
      </Card>
      <Card>
        <h2>{t("parsedLineItems")}</h2>
        {ocr.lineItems.length ? (
          <div className="table-like-list">
            {ocr.lineItems.map((lineItem) => (
              <article className="list-row line-item-row" key={`${lineItem.lineIndex}-${lineItem.id ?? lineItem.title}`}>
                <div className="list-row__main">
                  <strong>{lineItem.title}</strong>
                  <span>
                    {lineItem.quantity != null
                      ? `${formatQuantity(lineItem.quantity, language)}${lineItem.unit ? ` ${lineItem.unit}` : ""}`
                      : t("quantityUnavailable")}
                    {lineItem.unitPrice != null
                      ? ` / ${formatCurrency(lineItem.unitPrice, language, displayCurrency)}`
                      : ""}
                  </span>
                  {lineItem.rawFragment ? <span>{lineItem.rawFragment}</span> : null}
                </div>
                <div className="list-row__meta">
                  <span>{t("lineTotal")}</span>
                  <strong>
                    {lineItem.lineTotal != null
                      ? formatCurrency(lineItem.lineTotal, language, displayCurrency)
                      : "-"}
                  </strong>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState message={t("noLineItems")} />
        )}
      </Card>
      <Card>
        <h2>{t("rawOcrText")}</h2>
        <p className="field-hint">{t("rawTextHint")}</p>
        {ocr.rawOcrText ? (
          <pre className="code-panel">{ocr.rawOcrText}</pre>
        ) : (
          <EmptyState message={ocr.ocrStatus === "FAILED" ? t("ocrFailed") : t("ocrPending")} />
        )}
      </Card>
    </div>
  );
}
