package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.LinkedHashSet;
import java.util.List;

public record ParsedReceiptValidationResult(
    List<ReceiptParseWarningCode> warnings,
    boolean weakParseQuality
) {

    public ParsedReceiptValidationResult {
        warnings = List.copyOf(new LinkedHashSet<>(warnings));
    }

    public boolean hasWarning(ReceiptParseWarningCode warningCode) {
        return warnings.contains(warningCode);
    }
}
