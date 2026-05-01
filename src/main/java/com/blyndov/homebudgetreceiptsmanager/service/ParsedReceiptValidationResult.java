package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptProcessingDecision;
import java.util.LinkedHashSet;
import java.util.List;

public record ParsedReceiptValidationResult(
    List<ReceiptParseWarningCode> warnings,
    boolean weakParseQuality,
    ReceiptConfidence confidence,
    ReceiptProcessingDecision processingDecision
) {

    public ParsedReceiptValidationResult(List<ReceiptParseWarningCode> warnings, boolean weakParseQuality) {
        this(
            warnings,
            weakParseQuality,
            ReceiptConfidence.unknown(),
            weakParseQuality ? ReceiptProcessingDecision.NEEDS_REVIEW : ReceiptProcessingDecision.PARSED_OK
        );
    }

    public ParsedReceiptValidationResult {
        warnings = List.copyOf(new LinkedHashSet<>(warnings));
        confidence = confidence == null ? ReceiptConfidence.unknown() : confidence;
        processingDecision = processingDecision == null
            ? ReceiptProcessingDecision.PARSED_LOW_CONFIDENCE
            : processingDecision;
    }

    public boolean hasWarning(ReceiptParseWarningCode warningCode) {
        return warnings.contains(warningCode);
    }
}
