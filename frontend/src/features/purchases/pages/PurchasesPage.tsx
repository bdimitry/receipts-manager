import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { useEffect, useMemo, useState } from "react";
import { z } from "zod";
import {
  createPurchase,
  deletePurchase,
  getPurchases,
  type PurchaseFilters,
} from "../api";
import {
  derivePurchaseItemTotal,
  derivePurchaseItemsTotal,
  hasMeaningfulPurchaseItem,
  toPurchaseItemRequest,
  type PurchaseItemDraft,
} from "../lib/items";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { DEFAULT_CURRENCY, SUPPORTED_CURRENCIES } from "../../../shared/lib/currency";
import { getCategoryLabel, getCurrencyLabel } from "../../../shared/lib/domain";
import { formatCurrency, formatDate } from "../../../shared/lib/format";
import { Button } from "../../../shared/ui/Button";
import { CalculatorModal } from "../../../shared/ui/CalculatorModal";
import { Card } from "../../../shared/ui/Card";
import { EmptyState } from "../../../shared/ui/EmptyState";
import { ErrorState } from "../../../shared/ui/ErrorState";
import { LoadingState } from "../../../shared/ui/LoadingState";
import { PageIntro } from "../../../shared/ui/PageIntro";

const optionalPositiveNumber = z.preprocess((value) => {
  if (value === "" || value === null || value === undefined) {
    return undefined;
  }

  return Number(value);
}, z.number().positive().optional());

const purchaseItemSchema = z.object({
  title: z.string().trim().min(1),
  quantity: optionalPositiveNumber,
  unit: z.string().optional(),
  unitPrice: optionalPositiveNumber,
  lineTotal: optionalPositiveNumber,
});

const schema = z.object({
  title: z.string().trim().min(1),
  category: z.string().trim().min(1),
  amount: z.coerce.number().positive(),
  currency: z.enum(["USD", "EUR", "UAH", "RUB"]),
  purchaseDate: z.string().min(1),
  storeName: z.string().optional(),
  comment: z.string().optional(),
  items: z.array(purchaseItemSchema).default([]),
});

type PurchaseFormValues = z.infer<typeof schema>;
type PurchaseFormInput = z.input<typeof schema>;
type PurchaseFormItemInput = NonNullable<PurchaseFormInput["items"]>[number];

function getDefaultFilters(): Required<Pick<PurchaseFilters, "year" | "month">> {
  const now = new Date();
  return {
    year: now.getFullYear(),
    month: now.getMonth() + 1,
  };
}

function createEmptyItem(): PurchaseFormItemInput {
  return {
    title: "",
    quantity: undefined,
    unit: "",
    unitPrice: undefined,
    lineTotal: undefined,
  };
}

function getDefaultFormValues(): PurchaseFormValues {
  return {
    title: "",
    category: "",
    amount: 0,
    currency: DEFAULT_CURRENCY,
    purchaseDate: new Date().toISOString().slice(0, 10),
    storeName: "",
    comment: "",
    items: [],
  };
}

export function PurchasesPage() {
  const { t, language } = useI18n();
  const queryClient = useQueryClient();
  const defaultFilters = useMemo(() => getDefaultFilters(), []);
  const [filters, setFilters] = useState<PurchaseFilters>(defaultFilters);
  const [calculatorOpen, setCalculatorOpen] = useState(false);
  const {
    register,
    control,
    handleSubmit,
    reset,
    setValue,
    formState: { errors },
  } = useForm<PurchaseFormInput, unknown, PurchaseFormValues>({
    resolver: zodResolver(schema),
    defaultValues: getDefaultFormValues(),
  });
  const { fields, append, remove } = useFieldArray({
    control,
    name: "items",
  });
  const watchedItems = (useWatch({
    control,
    name: "items",
  }) ?? []) as PurchaseItemDraft[];
  const currentCurrency = useWatch({
    control,
    name: "currency",
  }) ?? DEFAULT_CURRENCY;

  const meaningfulItems = useMemo(
    () => watchedItems.filter(hasMeaningfulPurchaseItem),
    [watchedItems],
  );
  const itemsTotal = useMemo(
    () => derivePurchaseItemsTotal(meaningfulItems),
    [meaningfulItems],
  );
  const amountManagedByItems = meaningfulItems.length > 0 && itemsTotal > 0;

  const purchasesQuery = useQuery({
    queryKey: ["purchases", filters],
    queryFn: () => getPurchases(filters),
  });

  const createMutation = useMutation({
    mutationFn: createPurchase,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["purchases"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-purchases"] });
      reset(getDefaultFormValues());
      setCalculatorOpen(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: deletePurchase,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["purchases"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard-purchases"] });
    },
  });

  useEffect(() => {
    if (amountManagedByItems) {
      setValue("amount", itemsTotal, {
        shouldDirty: true,
        shouldValidate: true,
      });
    }
  }, [amountManagedByItems, itemsTotal, setValue]);

  return (
    <div className="page-grid page-grid--two-columns">
      <PageIntro title={t("purchases")} subtitle={t("purchasesSubtitle")} />
      <Card>
        <h2>{t("filterPeriod")}</h2>
        <div className="filter-grid">
          <label className="field">
            <span>{t("year")}</span>
            <input
              type="number"
              value={filters.year ?? ""}
              onChange={(event) =>
                setFilters((current) => ({
                  ...current,
                  year: event.target.value ? Number(event.target.value) : undefined,
                }))
              }
            />
          </label>
          <label className="field">
            <span>{t("month")}</span>
            <input
              type="number"
              min="1"
              max="12"
              value={filters.month ?? ""}
              onChange={(event) =>
                setFilters((current) => ({
                  ...current,
                  month: event.target.value ? Number(event.target.value) : undefined,
                }))
              }
            />
          </label>
          <label className="field field--wide">
            <span>{t("category")}</span>
            <input
              value={filters.category ?? ""}
              onChange={(event) =>
                setFilters((current) => ({
                  ...current,
                  category: event.target.value || undefined,
                }))
              }
            />
          </label>
        </div>
      </Card>
      <Card>
        <h2>{t("newPurchase")}</h2>
        <form
          className="form-grid"
          onSubmit={handleSubmit((values) => {
            const items = values.items
              .filter(hasMeaningfulPurchaseItem)
              .map(toPurchaseItemRequest);

            createMutation.mutate({
              ...values,
              amount: amountManagedByItems ? itemsTotal : values.amount,
              storeName: values.storeName || undefined,
              comment: values.comment || undefined,
              items: items.length ? items : undefined,
            });
          })}
        >
          <label className="field">
            <span>{t("title")}</span>
            <input {...register("title")} />
            {errors.title ? <small>{errors.title.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("category")}</span>
            <input {...register("category")} />
            {errors.category ? <small>{errors.category.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("amount")}</span>
            <div className="field-inline">
              <input
                step="0.01"
                type="number"
                readOnly={amountManagedByItems}
                {...register("amount")}
              />
              <Button
                className="field-inline__action"
                variant="ghost"
                onClick={() => setCalculatorOpen(true)}
              >
                {t("calculator")}
              </Button>
            </div>
            {amountManagedByItems ? <small>{t("amountCalculatedFromItems")}</small> : null}
            {errors.amount ? <small>{errors.amount.message}</small> : null}
          </label>
          <label className="field">
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
          <label className="field">
            <span>{t("purchaseDate")}</span>
            <input type="date" {...register("purchaseDate")} />
            {errors.purchaseDate ? <small>{errors.purchaseDate.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("storeName")}</span>
            <input {...register("storeName")} />
          </label>
          <label className="field field--wide">
            <span>{t("comment")}</span>
            <textarea rows={3} {...register("comment")} />
          </label>

          <div className="purchase-items-section field--wide">
            <div className="purchase-items-section__header">
              <div>
                <h3>{t("purchaseItems")}</h3>
                <p className="field-hint">{t("purchaseItemsHint")}</p>
              </div>
              <Button variant="ghost" onClick={() => append(createEmptyItem())}>
                {t("addItem")}
              </Button>
            </div>
            {fields.length ? (
              <div className="purchase-items-list">
                {fields.map((field, index) => {
                  const itemValue = watchedItems[index] ?? (createEmptyItem() as PurchaseItemDraft);
                  const derivedTotal = derivePurchaseItemTotal(itemValue);
                  return (
                    <div className="purchase-item-card" key={field.id}>
                      <div className="purchase-item-grid">
                        <label className="field purchase-item-grid__title">
                          <span>{`${t("itemTitle")} ${index + 1}`}</span>
                          <input {...register(`items.${index}.title`)} />
                          {errors.items?.[index]?.title ? (
                            <small>{errors.items[index]?.title?.message}</small>
                          ) : null}
                        </label>
                        <label className="field">
                          <span>{t("quantity")}</span>
                          <input step="0.001" type="number" {...register(`items.${index}.quantity`)} />
                          {errors.items?.[index]?.quantity ? (
                            <small>{errors.items[index]?.quantity?.message}</small>
                          ) : null}
                        </label>
                        <label className="field">
                          <span>{t("unit")}</span>
                          <input {...register(`items.${index}.unit`)} />
                        </label>
                        <label className="field">
                          <span>{t("unitPrice")}</span>
                          <input step="0.01" type="number" {...register(`items.${index}.unitPrice`)} />
                          {errors.items?.[index]?.unitPrice ? (
                            <small>{errors.items[index]?.unitPrice?.message}</small>
                          ) : null}
                        </label>
                        <label className="field">
                          <span>{t("lineTotal")}</span>
                          <input step="0.01" type="number" {...register(`items.${index}.lineTotal`)} />
                          {derivedTotal ? (
                            <small>{`${t("itemsTotal")}: ${formatCurrency(derivedTotal, language, currentCurrency)}`}</small>
                          ) : null}
                          {errors.items?.[index]?.lineTotal ? (
                            <small>{errors.items[index]?.lineTotal?.message}</small>
                          ) : null}
                        </label>
                        <div className="purchase-item-grid__actions">
                          <Button variant="ghost" onClick={() => remove(index)}>
                            {t("removeItem")}
                          </Button>
                        </div>
                      </div>
                    </div>
                  );
                })}
                <div className="purchase-items-summary">
                  <span>{t("itemsTotal")}</span>
                  <strong>{formatCurrency(itemsTotal, language, currentCurrency)}</strong>
                </div>
              </div>
            ) : (
              <EmptyState message={t("noPurchaseItems")} />
            )}
          </div>

          {createMutation.isError ? <p className="form-error">{createMutation.error.message}</p> : null}
          {createMutation.isSuccess ? <p className="form-success">{t("purchaseSavedSuccess")}</p> : null}
          <Button disabled={createMutation.isPending} type="submit">
            {t("create")}
          </Button>
        </form>
      </Card>
      <Card className="card--full">
        <div className="section-card__header">
          <div>
            <h2>{t("purchases")}</h2>
            <p>{t("purchasesSubtitle")}</p>
          </div>
        </div>
        {purchasesQuery.isLoading ? <LoadingState label={t("loading")} /> : null}
        {purchasesQuery.isError ? (
          <ErrorState title={t("errorTitle")} message={purchasesQuery.error.message} onRetry={purchasesQuery.refetch} />
        ) : null}
        {purchasesQuery.data && purchasesQuery.data.length === 0 ? (
          <EmptyState message={t("emptyPurchases")} />
        ) : null}
        {purchasesQuery.data?.length ? (
          <div className="table-like-list">
            {purchasesQuery.data.map((purchase) => (
              <article className="list-row list-row--purchase" key={purchase.id}>
                <div className="list-row__main">
                  <strong>{purchase.title}</strong>
                  <span>
                    {getCategoryLabel(purchase.category, t)} / {formatDate(purchase.purchaseDate, language)} /{" "}
                    {purchase.storeName ?? "-"}
                  </span>
                  {purchase.items.length ? (
                    <div className="purchase-item-preview-list">
                      <small>{`${purchase.items.length} ${t("recognizedItems")}`}</small>
                      <div className="purchase-item-pill-row">
                        {purchase.items.slice(0, 3).map((item, index) => (
                          <span className="purchase-item-pill" key={`${purchase.id}-${item.lineIndex ?? index}-${item.title}`}>
                            {item.title}
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>
                <div className="list-row__meta">
                  <strong>{formatCurrency(purchase.amount, language, purchase.currency)}</strong>
                  <Button
                    disabled={deleteMutation.isPending}
                    variant="ghost"
                    onClick={() => deleteMutation.mutate(purchase.id)}
                  >
                    {t("delete")}
                  </Button>
                </div>
              </article>
            ))}
          </div>
        ) : null}
      </Card>
      <CalculatorModal
        title={t("calculator")}
        closeLabel={t("close")}
        clearLabel={t("clear")}
        applyLabel={t("applyToAmount")}
        expressionLabel={t("expression")}
        resultLabel={t("lineTotal")}
        open={calculatorOpen}
        onApply={(value) => {
          setValue("amount", value, { shouldDirty: true, shouldValidate: true });
          setCalculatorOpen(false);
        }}
        onClose={() => setCalculatorOpen(false)}
      />
    </div>
  );
}
