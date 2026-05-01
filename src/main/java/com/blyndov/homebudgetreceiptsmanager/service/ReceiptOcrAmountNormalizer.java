package com.blyndov.homebudgetreceiptsmanager.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class ReceiptOcrAmountNormalizer {

    private static final String OCR_DIGITS = "0-9Oo\u041E\u043EIl\u0406\u0456|";
    private static final String KNOWN_CURRENCY_SUFFIX = "(?:uah|rph|rpn|tph|toh|reh|teh|\\u0433\\u0440\\u043D|\\u20B4)";
    private static final Pattern AMOUNT_TOKEN_PATTERN = Pattern.compile(
        "(?iu)(?<![\\p{L}\\d])([" + OCR_DIGITS + "]{1,5}(?:[ \\u00A0][" + OCR_DIGITS + "]{3})*[\\.,:][" + OCR_DIGITS + "]{2})(?=$|[^\\p{L}\\d]|" + KNOWN_CURRENCY_SUFFIX + "(?![\\p{L}\\d]))"
    );

    public List<AmountToken> findCandidates(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        Matcher matcher = AMOUNT_TOKEN_PATTERN.matcher(text);
        List<AmountToken> candidates = new ArrayList<>();
        while (matcher.find()) {
            normalize(matcher.group(1)).ifPresent(normalized -> candidates.add(
                new AmountToken(matcher.group(1), normalized, matcher.start(1), matcher.end(1))
            ));
        }
        return List.copyOf(candidates);
    }

    public Optional<ReceiptOcrFieldNormalizationResult> normalize(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return Optional.empty();
        }

        LinkedHashSet<String> actions = new LinkedHashSet<>();
        String normalized = rawValue.trim().replace('\u00A0', ' ');
        if (normalized.contains(" ")) {
            normalized = normalized.replace(" ", "");
            actions.add("amount_grouping_removed");
        }
        if (normalized.indexOf(',') >= 0 || normalized.indexOf(':') >= 0) {
            normalized = normalized.replace(',', '.').replace(':', '.');
            actions.add("amount_decimal_separator_normalized");
        }

        String digitFixed = fixNumericOcrDigits(normalized);
        if (!digitFixed.equals(normalized)) {
            normalized = digitFixed;
            actions.add("amount_ocr_digit_correction");
        }

        if (!normalized.matches("\\d{1,8}\\.\\d{2}")) {
            return Optional.empty();
        }

        try {
            return Optional.of(new ReceiptOcrFieldNormalizationResult(
                rawValue,
                new BigDecimal(normalized).toPlainString(),
                List.copyOf(actions)
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private String fixNumericOcrDigits(String value) {
        return value
            .replace('O', '0')
            .replace('o', '0')
            .replace('\u041E', '0')
            .replace('\u043E', '0')
            .replace('I', '1')
            .replace('l', '1')
            .replace('\u0406', '1')
            .replace('\u0456', '1')
            .replace('|', '1');
    }

    public record AmountToken(
        String rawValue,
        ReceiptOcrFieldNormalizationResult normalized,
        int start,
        int end
    ) {
    }
}
