package com.blyndov.homebudgetreceiptsmanager.service;

public record ReceiptConfidence(
    double ocrConfidence,
    double imageQualityConfidence,
    double reconstructionConfidence,
    double fieldExtractionConfidence,
    double businessConsistencyConfidence,
    double overallReceiptConfidence
) {

    public ReceiptConfidence {
        ocrConfidence = clamp(ocrConfidence);
        imageQualityConfidence = clamp(imageQualityConfidence);
        reconstructionConfidence = clamp(reconstructionConfidence);
        fieldExtractionConfidence = clamp(fieldExtractionConfidence);
        businessConsistencyConfidence = clamp(businessConsistencyConfidence);
        overallReceiptConfidence = clamp(overallReceiptConfidence);
    }

    public static ReceiptConfidence unknown() {
        return new ReceiptConfidence(0.5d, 0.5d, 0.5d, 0.5d, 0.5d, 0.5d);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
