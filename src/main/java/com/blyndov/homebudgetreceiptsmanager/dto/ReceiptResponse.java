package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ReceiptResponse(
    Long id,
    Long purchaseId,
    String originalFileName,
    String contentType,
    Long fileSize,
    CurrencyCode currency,
    ReceiptCountryHint receiptCountryHint,
    String s3Key,
    Instant uploadedAt,
    ReceiptOcrStatus ocrStatus,
    String parsedStoreName,
    BigDecimal parsedTotalAmount,
    LocalDate parsedPurchaseDate,
    Integer parsedLineItemCount,
    String ocrErrorMessage,
    Instant ocrProcessedAt
) {
}
