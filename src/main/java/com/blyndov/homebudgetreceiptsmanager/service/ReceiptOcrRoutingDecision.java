package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;

public record ReceiptOcrRoutingDecision(
    ReceiptCountryHint receiptCountryHint,
    OcrLanguageDetectionSource detectionSource,
    String ocrProfileStrategy,
    String ocrProfileUsed,
    OcrExtractionResult extractionResult
) {
}
