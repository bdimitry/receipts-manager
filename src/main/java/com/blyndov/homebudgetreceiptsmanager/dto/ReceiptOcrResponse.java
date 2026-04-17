package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReceiptOcrResponse(
    Long receiptId,
    CurrencyCode currency,
    ReceiptOcrStatus ocrStatus,
    String rawOcrText,
    List<NormalizedOcrLineResponse> normalizedLines,
    String parsedStoreName,
    BigDecimal parsedTotalAmount,
    CurrencyCode parsedCurrency,
    LocalDate parsedPurchaseDate,
    List<ReceiptLineItemResponse> lineItems,
    List<String> parseWarnings,
    boolean weakParseQuality,
    String ocrErrorMessage,
    Instant ocrProcessedAt
) {
}
