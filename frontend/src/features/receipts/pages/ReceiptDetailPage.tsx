import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { getReceipt, getReceiptOcr, submitReceiptCorrection } from "../api";
import { getCurrentUser } from "../../user/api";
import { useI18n } from "../../../shared/i18n/I18nContext";
import type { TranslationKey } from "../../../shared/i18n/translations";
import { getOcrStatusLabel } from "../../../shared/lib/domain";
import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from "../../../shared/lib/currency";
import { formatCurrency, formatDate, formatDateTime } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { StatusBadge } from "../../../shared/ui/StatusBadge";

interface CorrectionItemDraft {
  title: string;
  quantity: string;
  unit: string;
  unitPrice: string;
  lineTotal: string;
}

function toOptionalNumber(value: string) {
  if (!value.trim()) {
    return undefined;
  }
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? numericValue : undefined;
}

function toCorrectionItems(items: CorrectionItemDraft[]) {
  return items
    .filter((item) => item.title.trim() || item.lineTotal.trim() || item.unitPrice.trim())
    .map((item) => ({
      title: item.title.trim(),
      quantity: toOptionalNumber(item.quantity),
      unit: item.unit.trim() || undefined,
      unitPrice: toOptionalNumber(item.unitPrice),
      lineTotal: toOptionalNumber(item.lineTotal),
    }));
}

function emptyCorrectionItem(): CorrectionItemDraft {
  return {
    title: "",
    quantity: "",
    unit: "",
    unitPrice: "",
    lineTotal: "",
  };
}

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

function getCountryHintLabel(code: string | null | undefined, t: (key: TranslationKey) => string) {
  switch (code) {
    case "UKRAINE":
      return t("receiptCountryUkraine");
    case "POLAND":
      return t("receiptCountryPoland");
    case "GERMANY":
      return t("receiptCountryGermany");
    default:
      return t("receiptCountryAutoDetect");
  }
}

function getRoutingSourceLabel(code: string | null | undefined, t: (key: TranslationKey) => string) {
  switch (code) {
    case "USER_SELECTED":
      return t("ocrRoutingUserSelected");
    case "AUTO_DETECTED":
      return t("ocrRoutingAutoDetected");
    case "DEFAULT_FALLBACK":
      return t("ocrRoutingDefaultFallback");
    default:
      return "-";
  }
}

export function ReceiptDetailPage() {
  const { t, language } = useI18n();
  const queryClient = useQueryClient();
  const params = useParams();
  const receiptId = Number(params.id);
  const [activeTab, setActiveTab] = useState<"summary" | "complete">("summary");
  const [correctedStoreName, setCorrectedStoreName] = useState("");
  const [correctedCategory, setCorrectedCategory] = useState("OTHER");
  const [correctedPurchaseDate, setCorrectedPurchaseDate] = useState("");
  const [correctedTotalAmount, setCorrectedTotalAmount] = useState("");
  const [correctedCurrency, setCorrectedCurrency] = useState(DEFAULT_CURRENCY);
  const [correctedItems, setCorrectedItems] = useState<CorrectionItemDraft[]>([emptyCorrectionItem()]);
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
  const currentUserQuery = useQuery({
    queryKey: ["current-user"],
    queryFn: getCurrentUser,
  });
  const correctionMutation = useMutation({
    mutationFn: () =>
      submitReceiptCorrection(receiptId, {
        correctedStoreName: correctedStoreName.trim() || undefined,
        correctedCategory: correctedCategory.trim() || "OTHER",
        correctedPurchaseDate: correctedPurchaseDate || undefined,
        correctedTotalAmount: toOptionalNumber(correctedTotalAmount),
        correctedCurrency,
        correctedItems: toCorrectionItems(correctedItems),
        confirmed: false,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["receipt", receiptId] });
      queryClient.invalidateQueries({ queryKey: ["receipt-ocr", receiptId] });
      queryClient.invalidateQueries({ queryKey: ["receipts"] });
      queryClient.invalidateQueries({ queryKey: ["purchases"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-purchases"] });
      setActiveTab("summary");
    },
  });

  useEffect(() => {
    const ocr = ocrQuery.data;
    if (!ocr) {
      return;
    }

    const latest = ocr.latestCorrection?.correctedSnapshot;
    const items = latest?.items?.length ? latest.items : ocr.lineItems;
    setCorrectedStoreName(latest?.storeName ?? ocr.parsedStoreName ?? "");
    setCorrectedCategory(latest?.category ?? "OTHER");
    setCorrectedPurchaseDate(latest?.purchaseDate ?? ocr.parsedPurchaseDate ?? "");
    setCorrectedTotalAmount(String(latest?.totalAmount ?? ocr.parsedTotalAmount ?? ""));
    setCorrectedCurrency(latest?.currency ?? ocr.parsedCurrency ?? ocr.currency ?? DEFAULT_CURRENCY);
    setCorrectedItems(
      items.length
        ? items.map((item) => ({
            title: item.title ?? "",
            quantity: item.quantity == null ? "" : String(item.quantity),
            unit: item.unit ?? "",
            unitPrice: item.unitPrice == null ? "" : String(item.unitPrice),
            lineTotal: item.lineTotal == null ? "" : String(item.lineTotal),
          }))
        : [emptyCorrectionItem()],
    );
  }, [ocrQuery.data]);

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
  const currentUser = currentUserQuery.data;
  const isAdmin = Boolean(currentUser?.admin);

  if (!receipt || !ocr) {
    return <ErrorState title={t("errorTitle")} message={t("noData")} />;
  }

  const displayCurrency = ocr.parsedCurrency ?? ocr.currency;
  const parseWarnings = ocr.parseWarnings ?? [];
  const weakParseQuality = ocr.weakParseQuality ?? false;
  const updateCorrectionItem = (index: number, patch: Partial<CorrectionItemDraft>) => {
    setCorrectedItems((current) =>
      current.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)),
    );
  };

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
        <div className="tab-list" role="tablist" aria-label={t("receiptReviewTabs")}>
          <button
            className={`tab-button ${activeTab === "summary" ? "tab-button--active" : ""}`.trim()}
            type="button"
            onClick={() => setActiveTab("summary")}
          >
            {t("receiptSummaryTab")}
          </button>
          <button
            className={`tab-button ${activeTab === "complete" ? "tab-button--active" : ""}`.trim()}
            type="button"
            onClick={() => setActiveTab("complete")}
          >
            {t("receiptCompleteTab")}
          </button>
        </div>
      </Card>
      {activeTab === "complete" ? (
        <Card>
          <div className="section-card__header">
            <div>
              <h2>{t("receiptCompleteTitle")}</h2>
              <p>{t("receiptCompleteHint")}</p>
            </div>
            {ocr.latestCorrection ? <StatusBadge tone="success">{t("receiptCorrected")}</StatusBadge> : null}
          </div>
          <form
            className="form-grid"
            onSubmit={(event) => {
              event.preventDefault();
              correctionMutation.mutate();
            }}
          >
            <label className="field">
              <span>{t("storeName")}</span>
              <input value={correctedStoreName} onChange={(event) => setCorrectedStoreName(event.target.value)} />
            </label>
            <label className="field">
              <span>{t("category")}</span>
              <input value={correctedCategory} onChange={(event) => setCorrectedCategory(event.target.value)} />
            </label>
            <label className="field">
              <span>{t("purchaseDate")}</span>
              <input type="date" value={correctedPurchaseDate} onChange={(event) => setCorrectedPurchaseDate(event.target.value)} />
            </label>
            <label className="field">
              <span>{t("amount")}</span>
              <input step="0.01" type="number" value={correctedTotalAmount} onChange={(event) => setCorrectedTotalAmount(event.target.value)} />
            </label>
            <label className="field">
              <span>{t("currency")}</span>
              <select value={correctedCurrency} onChange={(event) => setCorrectedCurrency(event.target.value as typeof correctedCurrency)}>
                {SUPPORTED_CURRENCIES.map((currency) => (
                  <option key={currency} value={currency}>{currency}</option>
                ))}
              </select>
            </label>
            <div className="purchase-items-section field--wide">
              <div className="purchase-items-section__header">
                <div>
                  <h3>{t("parsedLineItems")}</h3>
                  <p className="field-hint">{t("receiptCompleteItemsHint")}</p>
                </div>
                <Button variant="ghost" onClick={() => setCorrectedItems((current) => [...current, emptyCorrectionItem()])}>
                  {t("addItem")}
                </Button>
              </div>
              <div className="purchase-items-list">
                {correctedItems.map((item, index) => (
                  <div className="purchase-item-card" key={`${index}-${item.title}`}>
                    <div className="purchase-item-grid">
                      <label className="field purchase-item-grid__title">
                        <span>{`${t("itemTitle")} ${index + 1}`}</span>
                        <input value={item.title} onChange={(event) => updateCorrectionItem(index, { title: event.target.value })} />
                      </label>
                      <label className="field">
                        <span>{t("quantity")}</span>
                        <input step="0.001" type="number" value={item.quantity} onChange={(event) => updateCorrectionItem(index, { quantity: event.target.value })} />
                      </label>
                      <label className="field">
                        <span>{t("unit")}</span>
                        <input value={item.unit} onChange={(event) => updateCorrectionItem(index, { unit: event.target.value })} />
                      </label>
                      <label className="field">
                        <span>{t("unitPrice")}</span>
                        <input step="0.01" type="number" value={item.unitPrice} onChange={(event) => updateCorrectionItem(index, { unitPrice: event.target.value })} />
                      </label>
                      <label className="field">
                        <span>{t("lineTotal")}</span>
                        <input step="0.01" type="number" value={item.lineTotal} onChange={(event) => updateCorrectionItem(index, { lineTotal: event.target.value })} />
                      </label>
                      <div className="purchase-item-grid__actions">
                        <Button variant="ghost" onClick={() => setCorrectedItems((current) => current.filter((_, itemIndex) => itemIndex !== index))}>
                          {t("removeItem")}
                        </Button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            {correctionMutation.isError ? <p className="form-error">{correctionMutation.error.message}</p> : null}
            {correctionMutation.isSuccess ? <p className="form-success">{t("receiptCorrectionSaved")}</p> : null}
            <Button disabled={correctionMutation.isPending} type="submit">
              {t("save")}
            </Button>
          </form>
        </Card>
      ) : null}
      {activeTab === "summary" ? (
      <>
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
          {isAdmin ? (
            <div>
              <dt>{t("s3Key")}</dt>
              <dd>{receipt.s3Key}</dd>
            </div>
          ) : null}
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
        <h2>{t("ocrRouting")}</h2>
        <dl className="detail-grid">
          <div>
            <dt>{t("receiptCountryHint")}</dt>
            <dd>{getCountryHintLabel(ocr.receiptCountryHint, t)}</dd>
          </div>
          <div>
            <dt>{t("ocrDetectionSource")}</dt>
            <dd>{getRoutingSourceLabel(ocr.languageDetectionSource, t)}</dd>
          </div>
          <div>
            <dt>{t("ocrProfileStrategy")}</dt>
            <dd>{ocr.ocrProfileStrategy ?? "-"}</dd>
          </div>
          <div>
            <dt>{t("ocrProfileUsed")}</dt>
            <dd>{ocr.ocrProfileUsed ?? "-"}</dd>
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
                  {isAdmin && lineItem.rawFragment ? <span>{lineItem.rawFragment}</span> : null}
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
      {isAdmin ? (
        <Card>
          <h2>{t("rawOcrText")}</h2>
          <p className="field-hint">{t("rawTextHint")}</p>
          {ocr.rawOcrText ? (
            <pre className="code-panel">{ocr.rawOcrText}</pre>
          ) : (
            <EmptyState message={ocr.ocrStatus === "FAILED" ? t("ocrFailed") : t("ocrPending")} />
          )}
        </Card>
      ) : null}
      </>
      ) : null}
    </div>
  );
}
