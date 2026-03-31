package com.blyndov.homebudgetreceiptsmanager.dto;

import java.math.BigDecimal;

public record ReceiptLineItemResponse(
    Long id,
    Integer lineIndex,
    String title,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String rawFragment
) {
}
