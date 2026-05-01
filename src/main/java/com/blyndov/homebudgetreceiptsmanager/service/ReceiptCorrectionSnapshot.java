package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReceiptCorrectionSnapshot(
    String storeName,
    LocalDate purchaseDate,
    BigDecimal totalAmount,
    CurrencyCode currency,
    List<ReceiptCorrectionLineItemSnapshot> items
) {

    public ReceiptCorrectionSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
