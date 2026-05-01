package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.List;

public record ReceiptOcrFieldCandidate(
    ReceiptOcrCandidateType type,
    Integer sourceLineOrder,
    String sourceZone,
    String rawText,
    String normalizedValue,
    List<String> normalizationActions,
    Double ocrConfidence,
    double parserScore,
    List<String> scoringReasons
) {

    public ReceiptOcrFieldCandidate {
        normalizationActions = normalizationActions == null ? List.of() : List.copyOf(normalizationActions);
        scoringReasons = scoringReasons == null ? List.of() : List.copyOf(scoringReasons);
    }
}
