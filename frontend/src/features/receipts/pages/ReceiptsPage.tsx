import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { Link } from "react-router-dom";
import { z } from "zod";
import { getPurchases } from "../../purchases/api";
import { getReceipts, uploadReceipt } from "../api";
import type { ReceiptResponse } from "../../../shared/api/types";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from "../../../shared/lib/currency";
import { getCategoryLabel, getCurrencyLabel, getOcrStatusLabel } from "../../../shared/lib/domain";
import { formatCurrency, formatDateTime } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";
import { StatusBadge } from "../../../shared/ui/StatusBadge";

const schema = z.object({
  file: z
    .custom<FileList>()
    .refine((value) => value instanceof FileList && value.length > 0, "Please choose a file"),
  purchaseId: z.string().optional(),
  currency: z.enum(["USD", "EUR", "UAH", "RUB"]),
  category: z.string().trim().optional(),
});

type ReceiptFormValues = z.infer<typeof schema>;

function mapOcrTone(status: string) {
  if (status === "DONE") {
    return "success";
  }

  if (status === "FAILED") {
    return "danger";
  }

  return "warning";
}

export function ReceiptsPage() {
  const { t, language } = useI18n();
  const queryClient = useQueryClient();
  const purchasesQuery = useQuery({
    queryKey: ["receipt-purchases"],
    queryFn: () => getPurchases(),
  });
  const receiptsQuery = useQuery({
    queryKey: ["receipts"],
    queryFn: getReceipts,
    refetchInterval: (query) =>
      query.state.data?.some((receipt) => receipt.ocrStatus === "NEW" || receipt.ocrStatus === "PROCESSING")
        ? 4_000
        : false,
  });
  const {
    register,
    handleSubmit,
    reset,
    watch,
    setValue,
    formState: { errors },
  } = useForm<ReceiptFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      purchaseId: "",
      currency: DEFAULT_CURRENCY,
      category: "",
    },
  });
  const selectedPurchaseId = watch("purchaseId");

  const uploadMutation = useMutation({
    mutationFn: (values: ReceiptFormValues) =>
      uploadReceipt(
        values.file[0],
        values.currency,
        values.purchaseId ? Number(values.purchaseId) : undefined,
        values.category,
      ),
    onSuccess: (createdReceipt) => {
      queryClient.setQueryData<ReceiptResponse[]>(["receipts"], (currentReceipts) => {
        const existingReceipts = currentReceipts ?? [];
        return [createdReceipt, ...existingReceipts.filter((receipt) => receipt.id !== createdReceipt.id)];
      });
      queryClient.invalidateQueries({ queryKey: ["dashboard-receipts"] });
      reset({
        purchaseId: "",
        currency: DEFAULT_CURRENCY,
        category: "",
      });
    },
  });

  useEffect(() => {
    if (!selectedPurchaseId) {
      return;
    }

    const linkedPurchase = purchasesQuery.data?.find((purchase) => purchase.id === Number(selectedPurchaseId));
    if (linkedPurchase) {
      setValue("currency", linkedPurchase.currency, { shouldDirty: true });
      setValue("category", linkedPurchase.category, { shouldDirty: true });
    } else {
      setValue("category", "", { shouldDirty: true });
    }
  }, [purchasesQuery.data, selectedPurchaseId, setValue]);

  return (
    <div className="page-grid page-grid--two-columns">
      <PageIntro title={t("receipts")} subtitle={t("receiptsSubtitle")} />
      <Card>
        <h2>{t("uploadReceipt")}</h2>
        <form className="form-grid" onSubmit={handleSubmit((values) => uploadMutation.mutate(values))}>
          <label className="field field--wide">
            <span>{t("receiptFile")}</span>
            <input type="file" accept="image/png,image/jpeg,application/pdf" {...register("file")} />
            {errors.file ? <small>{errors.file.message as string}</small> : null}
          </label>
          <label className="field field--wide">
            <span>
              {t("linkToPurchase")} ({t("optional")})
            </span>
            <select {...register("purchaseId")}>
              <option value="">-</option>
              {purchasesQuery.data?.map((purchase) => (
                <option key={purchase.id} value={purchase.id}>
                  {purchase.title} / {getCurrencyLabel(purchase.currency, t)}
                </option>
              ))}
            </select>
          </label>
          <label className="field field--wide">
            <span>{t("currency")}</span>
            <select {...register("currency")}>
              {SUPPORTED_CURRENCIES.map((currency) => (
                <option key={currency} value={currency}>
                  {getCurrencyLabel(currency, t)}
                </option>
              ))}
            </select>
            {errors.currency ? <small>{errors.currency.message}</small> : null}
          </label>
          <label className="field field--wide">
            <span>
              {t("category")} ({t("optional")})
            </span>
            <input readOnly={Boolean(selectedPurchaseId)} {...register("category")} />
          </label>
          {uploadMutation.isError ? <p className="form-error">{uploadMutation.error.message}</p> : null}
          {uploadMutation.isSuccess ? <p className="form-success">{t("receiptUploadSuccess")}</p> : null}
          <Button disabled={uploadMutation.isPending} type="submit">
            {t("uploadReceipt")}
          </Button>
        </form>
      </Card>
      <Card className="card--full">
        <div className="section-card__header">
          <div>
            <h2>{t("receipts")}</h2>
            <p>{t("receiptsSubtitle")}</p>
          </div>
        </div>
        {receiptsQuery.isLoading ? <LoadingState label={t("loading")} /> : null}
        {receiptsQuery.isError ? (
          <ErrorState title={t("errorTitle")} message={receiptsQuery.error.message} onRetry={receiptsQuery.refetch} />
        ) : null}
        {receiptsQuery.data && receiptsQuery.data.length === 0 ? (
          <EmptyState message={t("emptyReceipts")} />
        ) : null}
        {receiptsQuery.data?.length ? (
          <div className="table-like-list">
            {receiptsQuery.data.map((receipt) => (
              <article className="list-row" key={receipt.id}>
                <div className="list-row__main">
                  <strong>{receipt.originalFileName}</strong>
                  <span>
                    {formatDateTime(receipt.uploadedAt, language)}
                    {receipt.parsedStoreName ? ` / ${receipt.parsedStoreName}` : ""}
                    {receipt.category ? ` / ${getCategoryLabel(receipt.category, t)}` : ""}
                    {receipt.parsedTotalAmount ? ` / ${formatCurrency(receipt.parsedTotalAmount, language, receipt.currency)}` : ""}
                    {receipt.parsedLineItemCount ? ` / ${receipt.parsedLineItemCount} ${t("recognizedItems")}` : ""}
                  </span>
                </div>
                <div className="list-row__meta">
                  <StatusBadge tone={mapOcrTone(receipt.ocrStatus)}>
                    {getOcrStatusLabel(receipt.ocrStatus, t)}
                  </StatusBadge>
                  <Link to={`/receipts/${receipt.id}`}>
                    <Button variant="ghost">{t("details")}</Button>
                  </Link>
                </div>
              </article>
            ))}
          </div>
        ) : null}
      </Card>
    </div>
  );
}
