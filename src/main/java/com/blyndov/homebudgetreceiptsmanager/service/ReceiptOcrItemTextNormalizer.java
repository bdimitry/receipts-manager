package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.LinkedHashSet;
import java.util.List;

public class ReceiptOcrItemTextNormalizer {

    public ReceiptOcrFieldNormalizationResult normalize(String rawValue) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        String normalized = rawValue == null ? "" : rawValue.trim();
        if (!normalized.equals(rawValue)) {
            actions.add("item_text_trimmed");
        }

        String collapsed = normalized.replaceAll("\\s+", " ");
        if (!collapsed.equals(normalized)) {
            normalized = collapsed;
            actions.add("item_whitespace_normalized");
        }

        return new ReceiptOcrFieldNormalizationResult(rawValue, normalized, List.copyOf(actions));
    }
}
