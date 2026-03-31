package com.blyndov.homebudgetreceiptsmanager.dto;

import java.math.BigDecimal;

public record PurchaseItemResponse(
    Long id,
    Integer lineIndex,
    String title,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {
}
