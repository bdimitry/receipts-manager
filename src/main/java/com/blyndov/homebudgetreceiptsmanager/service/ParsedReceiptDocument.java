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
    List<ParsedReceiptLineItem> lineItems,
    ReceiptOcrCandidateSet candidates
) {

    public ParsedReceiptDocument(
        String merchantName,
        LocalDate purchaseDate,
        BigDecimal totalAmount,
        CurrencyCode currency,
        List<ParsedReceiptLineItem> lineItems
    ) {
        this(merchantName, purchaseDate, totalAmount, currency, lineItems, null);
    }

    public ParsedReceiptDocument {
        lineItems = List.copyOf(lineItems);
        candidates = candidates == null
            ? new ReceiptOcrCandidateSet(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
            : candidates;
    }
}
