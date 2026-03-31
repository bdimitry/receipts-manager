package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PurchaseResponse(
    Long id,
    String title,
    String category,
    BigDecimal amount,
    CurrencyCode currency,
    LocalDate purchaseDate,
    String storeName,
    String comment,
    Instant createdAt,
    List<PurchaseItemResponse> items
) {
}
