package com.blyndov.homebudgetreceiptsmanager.dto;

public record ReceiptConfidenceResponse(
    double ocrConfidence,
    double imageQualityConfidence,
    double reconstructionConfidence,
    double fieldExtractionConfidence,
    double businessConsistencyConfidence,
    double overallReceiptConfidence
) {
}
