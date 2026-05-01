package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReceiptCorrectionSnapshotResponse(
    String storeName,
    LocalDate purchaseDate,
    BigDecimal totalAmount,
    CurrencyCode currency,
    List<ReceiptCorrectionLineItemResponse> items
) {
}
