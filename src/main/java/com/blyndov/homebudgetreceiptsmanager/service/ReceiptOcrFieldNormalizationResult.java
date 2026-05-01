package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.List;

public record ReceiptOcrFieldNormalizationResult(
    String rawValue,
    String normalizedValue,
    List<String> actions
) {

    public ReceiptOcrFieldNormalizationResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
