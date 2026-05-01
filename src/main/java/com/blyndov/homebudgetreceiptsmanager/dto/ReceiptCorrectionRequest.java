package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReceiptCorrectionRequest(
    String correctedStoreName,
    LocalDate correctedPurchaseDate,
    BigDecimal correctedTotalAmount,
    CurrencyCode correctedCurrency,
    List<ReceiptCorrectionItemRequest> correctedItems,
    boolean confirmed
) {
}
