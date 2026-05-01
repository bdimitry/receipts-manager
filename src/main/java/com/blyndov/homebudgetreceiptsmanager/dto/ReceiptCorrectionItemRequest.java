package com.blyndov.homebudgetreceiptsmanager.dto;

import java.math.BigDecimal;

public record ReceiptCorrectionItemRequest(
    String title,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {
}
