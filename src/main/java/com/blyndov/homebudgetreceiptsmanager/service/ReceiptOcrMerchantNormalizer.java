package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.util.StringUtils;

public class ReceiptOcrMerchantNormalizer {

    public ReceiptOcrFieldNormalizationResult normalize(String rawValue) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        String normalized = rawValue == null ? "" : rawValue.trim();
        if (!normalized.equals(rawValue)) {
            actions.add("merchant_text_trimmed");
        }

        String collapsed = normalized.replaceAll("\\s+", " ");
        if (!collapsed.equals(normalized)) {
            normalized = collapsed;
            actions.add("merchant_whitespace_normalized");
        }

        String trimmedPunctuation = normalized.replaceAll("[\\s,;:/\\\\|.-]+$", "");
        if (!trimmedPunctuation.equals(normalized)) {
            normalized = trimmedPunctuation;
            actions.add("merchant_trailing_punctuation_trimmed");
        }

        return new ReceiptOcrFieldNormalizationResult(rawValue, normalized, List.copyOf(actions));
    }

    public ReceiptOcrFieldNormalizationResult alias(String rawValue, String aliasValue) {
        ReceiptOcrFieldNormalizationResult base = normalize(rawValue);
        LinkedHashSet<String> actions = new LinkedHashSet<>(base.actions());
        if (StringUtils.hasText(aliasValue) && !aliasValue.equals(base.normalizedValue())) {
            actions.add("merchant_alias_normalized");
        }
        return new ReceiptOcrFieldNormalizationResult(rawValue, aliasValue, List.copyOf(actions));
    }
}
