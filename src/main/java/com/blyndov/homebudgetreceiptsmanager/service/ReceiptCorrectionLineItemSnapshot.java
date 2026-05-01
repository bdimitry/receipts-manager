package com.blyndov.homebudgetreceiptsmanager.service;

import java.math.BigDecimal;

public record ReceiptCorrectionLineItemSnapshot(
    String title,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {
}
