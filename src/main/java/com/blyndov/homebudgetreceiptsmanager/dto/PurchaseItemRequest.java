package com.blyndov.homebudgetreceiptsmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record PurchaseItemRequest(
    @NotBlank String title,
    @Positive BigDecimal quantity,
    String unit,
    @Positive BigDecimal unitPrice,
    @Positive BigDecimal lineTotal
) {
}
