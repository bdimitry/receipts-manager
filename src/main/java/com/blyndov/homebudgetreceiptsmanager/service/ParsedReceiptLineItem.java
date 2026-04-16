package com.blyndov.homebudgetreceiptsmanager.service;

import java.math.BigDecimal;
import java.util.List;

public record ParsedReceiptLineItem(
    Integer lineIndex,
    String title,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String rawFragment,
    List<String> sourceLines
) {
}
