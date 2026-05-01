package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionItemRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiptCorrectionDiffService {

    public ReceiptCorrectionSnapshot parsedSnapshot(Receipt receipt) {
        return new ReceiptCorrectionSnapshot(
            receipt.getParsedStoreName(),
            receipt.getParsedPurchaseDate(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedCurrency(),
            receipt.getLineItems().stream()
                .map(item -> new ReceiptCorrectionLineItemSnapshot(
                    item.getTitle(),
                    item.getQuantity(),
                    item.getUnit(),
                    item.getUnitPrice(),
                    item.getLineTotal()
                ))
                .toList()
        );
    }

    public ReceiptCorrectionSnapshot correctedSnapshot(
        ReceiptCorrectionSnapshot parsedSnapshot,
        ReceiptCorrectionRequest request
    ) {
        List<ReceiptCorrectionLineItemSnapshot> correctedItems = request.correctedItems() == null
            ? parsedSnapshot.items()
            : request.correctedItems().stream()
                .map(this::toSnapshot)
                .toList();

        return new ReceiptCorrectionSnapshot(
            StringUtils.hasText(request.correctedStoreName()) ? request.correctedStoreName().trim() : parsedSnapshot.storeName(),
            request.correctedPurchaseDate() == null ? parsedSnapshot.purchaseDate() : request.correctedPurchaseDate(),
            request.correctedTotalAmount() == null ? parsedSnapshot.totalAmount() : request.correctedTotalAmount(),
            request.correctedCurrency() == null ? parsedSnapshot.currency() : request.correctedCurrency(),
            correctedItems
        );
    }

    public List<ReceiptCorrectionFieldDiff> diff(
        ReceiptCorrectionSnapshot parsedSnapshot,
        ReceiptCorrectionSnapshot correctedSnapshot
    ) {
        List<ReceiptCorrectionFieldDiff> diffs = new ArrayList<>();
        addDiff(diffs, "storeName", parsedSnapshot.storeName(), correctedSnapshot.storeName());
        addDiff(diffs, "purchaseDate", parsedSnapshot.purchaseDate(), correctedSnapshot.purchaseDate());
        addMoneyDiff(diffs, "totalAmount", parsedSnapshot.totalAmount(), correctedSnapshot.totalAmount());
        addDiff(diffs, "currency", parsedSnapshot.currency(), correctedSnapshot.currency());
        if (!Objects.equals(parsedSnapshot.items(), correctedSnapshot.items())) {
            diffs.add(new ReceiptCorrectionFieldDiff(
                "items",
                String.valueOf(parsedSnapshot.items().size()),
                String.valueOf(correctedSnapshot.items().size())
            ));
        }
        return List.copyOf(diffs);
    }

    private ReceiptCorrectionLineItemSnapshot toSnapshot(ReceiptCorrectionItemRequest request) {
        return new ReceiptCorrectionLineItemSnapshot(
            request.title(),
            request.quantity(),
            request.unit(),
            request.unitPrice(),
            request.lineTotal()
        );
    }

    private void addDiff(List<ReceiptCorrectionFieldDiff> diffs, String field, Object parsedValue, Object correctedValue) {
        if (!Objects.equals(parsedValue, correctedValue)) {
            diffs.add(new ReceiptCorrectionFieldDiff(field, stringify(parsedValue), stringify(correctedValue)));
        }
    }

    private void addMoneyDiff(
        List<ReceiptCorrectionFieldDiff> diffs,
        String field,
        BigDecimal parsedValue,
        BigDecimal correctedValue
    ) {
        if (parsedValue == null || correctedValue == null) {
            addDiff(diffs, field, parsedValue, correctedValue);
            return;
        }

        if (parsedValue.compareTo(correctedValue) != 0) {
            diffs.add(new ReceiptCorrectionFieldDiff(field, parsedValue.toPlainString(), correctedValue.toPlainString()));
        }
    }

    private String stringify(Object value) {
        return value == null ? null : value.toString();
    }
}
