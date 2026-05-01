package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptProcessingDecision;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReceiptOcrResponse(
    Long receiptId,
    CurrencyCode currency,
    ReceiptOcrStatus ocrStatus,
    String rawOcrText,
    RawOcrArtifactResponse rawOcrArtifact,
    List<ReconstructedOcrLineResponse> reconstructedLines,
    List<NormalizedOcrLineResponse> normalizedLines,
    ReceiptCountryHint receiptCountryHint,
    OcrLanguageDetectionSource languageDetectionSource,
    String ocrProfileStrategy,
    String ocrProfileUsed,
    String parsedStoreName,
    BigDecimal parsedTotalAmount,
    CurrencyCode parsedCurrency,
    LocalDate parsedPurchaseDate,
    List<ReceiptLineItemResponse> lineItems,
    List<String> parseWarnings,
    boolean weakParseQuality,
    ReceiptConfidenceResponse ocrConfidence,
    ReceiptProcessingDecision ocrProcessingDecision,
    ReceiptReviewStatus reviewStatus,
    ReceiptCorrectionResponse latestCorrection,
    String ocrErrorMessage,
    Instant ocrProcessedAt
) {
}
