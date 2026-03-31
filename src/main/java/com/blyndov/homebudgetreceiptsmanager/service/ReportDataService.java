package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.Purchase;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class ReportDataService {

    private final PurchaseRepository purchaseRepository;

    public ReportDataService(PurchaseRepository purchaseRepository) {
        this.purchaseRepository = purchaseRepository;
    }

    public ReportDocument buildReportDocument(ReportJob reportJob) {
        List<Purchase> purchases = loadPurchases(reportJob);

        return switch (reportJob.getReportType()) {
            case MONTHLY_SPENDING -> buildMonthlySpendingDocument(reportJob, purchases);
            case CATEGORY_SUMMARY -> buildCategorySummaryDocument(reportJob, purchases);
            case STORE_SUMMARY -> buildStoreSummaryDocument(reportJob, purchases);
        };
    }

    private List<Purchase> loadPurchases(ReportJob reportJob) {
        YearMonth period = YearMonth.of(reportJob.getYear(), reportJob.getMonth());
        LocalDate startDate = period.atDay(1);
        LocalDate endDate = period.plusMonths(1).atDay(1);

        return purchaseRepository
            .findAllByUser_IdAndPurchaseDateGreaterThanEqualAndPurchaseDateLessThanOrderByPurchaseDateAscIdAsc(
                reportJob.getUser().getId(),
                startDate,
                endDate
            );
    }

    private ReportDocument buildMonthlySpendingDocument(ReportJob reportJob, List<Purchase> purchases) {
        List<List<String>> purchaseRows = purchases.stream()
            .map(purchase -> List.of(
                purchase.getPurchaseDate().toString(),
                purchase.getTitle(),
                purchase.getCategory(),
                defaultString(purchase.getStoreName()),
                purchase.getCurrency().name(),
                formatAmount(purchase.getAmount()),
                defaultString(purchase.getComment())
            ))
            .toList();

        List<List<String>> currencyTotalRows = totalsByCurrency(purchases).entrySet()
            .stream()
            .map(entry -> List.of(entry.getKey().name(), formatAmount(entry.getValue())))
            .toList();

        List<List<String>> categoryRows = aggregateByCategoryAndCurrency(purchases).entrySet()
            .stream()
            .map(entry -> List.of(
                entry.getKey().dimension(),
                entry.getKey().currency().name(),
                formatAmount(entry.getValue().total())
            ))
            .toList();

        return new ReportDocument(
            "Monthly Spending Report",
            defaultMetadata(reportJob, purchases),
            List.of(
                new ReportSection(
                    "Purchases",
                    List.of("purchaseDate", "title", "category", "storeName", "currency", "amount", "comment"),
                    purchaseRows,
                    "No purchases found for the selected period",
                    true
                ),
                new ReportSection(
                    "Totals by Currency",
                    List.of("currency", "totalAmount"),
                    currencyTotalRows,
                    "No currency totals available",
                    true
                ),
                new ReportSection(
                    "Category Summary",
                    List.of("category", "currency", "totalAmount"),
                    categoryRows,
                    "No category totals available",
                    true
                )
            )
        );
    }

    private ReportDocument buildCategorySummaryDocument(ReportJob reportJob, List<Purchase> purchases) {
        List<List<String>> rows = aggregateByCategoryAndCurrency(purchases).entrySet()
            .stream()
            .map(entry -> List.of(
                entry.getKey().dimension(),
                entry.getKey().currency().name(),
                String.valueOf(entry.getValue().count()),
                formatAmount(entry.getValue().total())
            ))
            .toList();

        return new ReportDocument(
            "Category Summary Report",
            defaultMetadata(reportJob, purchases),
            List.of(
                new ReportSection(
                    "Category Summary",
                    List.of("category", "currency", "purchaseCount", "totalAmount"),
                    rows,
                    "No category totals available",
                    true
                ),
                new ReportSection(
                    "Totals by Currency",
                    List.of("currency", "totalAmount"),
                    totalsByCurrencyRows(purchases),
                    "No currency totals available",
                    true
                ),
                new ReportSection(
                    "Summary",
                    List.of("metric", "value"),
                    List.of(List.of("Purchase Count", String.valueOf(purchases.size()))),
                    null,
                    true
                )
            )
        );
    }

    private ReportDocument buildStoreSummaryDocument(ReportJob reportJob, List<Purchase> purchases) {
        List<List<String>> rows = aggregateByStoreAndCurrency(purchases).entrySet()
            .stream()
            .map(entry -> List.of(
                entry.getKey().dimension(),
                entry.getKey().currency().name(),
                String.valueOf(entry.getValue().count()),
                formatAmount(entry.getValue().total())
            ))
            .toList();

        return new ReportDocument(
            "Store Summary Report",
            defaultMetadata(reportJob, purchases),
            List.of(
                new ReportSection(
                    "Store Summary",
                    List.of("storeName", "currency", "purchaseCount", "totalAmount"),
                    rows,
                    "No store totals available",
                    true
                ),
                new ReportSection(
                    "Totals by Currency",
                    List.of("currency", "totalAmount"),
                    totalsByCurrencyRows(purchases),
                    "No currency totals available",
                    true
                ),
                new ReportSection(
                    "Summary",
                    List.of("metric", "value"),
                    List.of(List.of("Purchase Count", String.valueOf(purchases.size()))),
                    null,
                    true
                )
            )
        );
    }

    private List<ReportMetadataItem> defaultMetadata(ReportJob reportJob, List<Purchase> purchases) {
        List<ReportMetadataItem> metadata = new ArrayList<>();
        metadata.add(new ReportMetadataItem("Period", YearMonth.of(reportJob.getYear(), reportJob.getMonth()).toString()));
        metadata.add(new ReportMetadataItem("Owner", reportJob.getUser().getEmail()));
        metadata.add(new ReportMetadataItem("Report Type", reportJob.getReportType().name()));
        metadata.add(new ReportMetadataItem("Currencies", currenciesMetadata(purchases)));
        return metadata;
    }

    private String currenciesMetadata(List<Purchase> purchases) {
        if (purchases.isEmpty()) {
            return "No purchases";
        }

        return totalsByCurrency(purchases).keySet().stream().map(Enum::name).reduce((left, right) -> left + ", " + right).orElse("No purchases");
    }

    private List<List<String>> totalsByCurrencyRows(List<Purchase> purchases) {
        return totalsByCurrency(purchases).entrySet()
            .stream()
            .map(entry -> List.of(entry.getKey().name(), formatAmount(entry.getValue())))
            .toList();
    }

    private Map<AggregateKey, AggregateRow> aggregateByCategoryAndCurrency(List<Purchase> purchases) {
        Map<AggregateKey, AggregateRow> summary = new LinkedHashMap<>();
        purchases.forEach(purchase -> summary.computeIfAbsent(
            new AggregateKey(purchase.getCategory(), purchase.getCurrency()),
            ignored -> new AggregateRow()
        ).add(purchase.getAmount()));
        return summary;
    }

    private Map<AggregateKey, AggregateRow> aggregateByStoreAndCurrency(List<Purchase> purchases) {
        Map<AggregateKey, AggregateRow> summary = new LinkedHashMap<>();
        purchases.forEach(purchase -> summary.computeIfAbsent(
            new AggregateKey(normalizeStoreName(purchase.getStoreName()), purchase.getCurrency()),
            ignored -> new AggregateRow()
        ).add(purchase.getAmount()));
        return summary;
    }

    private Map<CurrencyCode, BigDecimal> totalsByCurrency(List<Purchase> purchases) {
        Map<CurrencyCode, BigDecimal> totals = new LinkedHashMap<>();
        purchases.forEach(purchase -> totals.merge(purchase.getCurrency(), purchase.getAmount(), BigDecimal::add));
        return totals;
    }

    private String normalizeStoreName(String storeName) {
        return StringUtils.hasText(storeName) ? storeName : "Unknown Store";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static final class AggregateRow {

        private int count;
        private BigDecimal total = BigDecimal.ZERO;

        void add(BigDecimal amount) {
            count++;
            total = total.add(amount);
        }

        int count() {
            return count;
        }

        BigDecimal total() {
            return total;
        }
    }

    private record AggregateKey(String dimension, CurrencyCode currency) {
    }
}
