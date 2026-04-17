package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedReceiptDocument(
    String merchantName,
    LocalDate purchaseDate,
    BigDecimal totalAmount,
    CurrencyCode currency,
    List<ParsedReceiptLineItem> lineItems
) {

    public ParsedReceiptDocument {
        lineItems = List.copyOf(lineItems);
    }
}
