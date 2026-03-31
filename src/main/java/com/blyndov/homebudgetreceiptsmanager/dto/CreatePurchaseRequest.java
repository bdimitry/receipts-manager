package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreatePurchaseRequest(
    @NotBlank String title,
    @NotBlank String category,
    @NotNull @Positive BigDecimal amount,
    @NotNull CurrencyCode currency,
    @NotNull LocalDate purchaseDate,
    String storeName,
    String comment,
    List<@Valid PurchaseItemRequest> items
) {
}
