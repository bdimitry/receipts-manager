package com.blyndov.homebudgetreceiptsmanager.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class ReceiptOcrDateTimeNormalizer {

    private static final String OCR_DIGITS = "0-9Oo\u041E\u043EIl\u0406\u0456|";
    private static final Pattern DATE_TOKEN_PATTERN = Pattern.compile(
        "(?iu)([" + OCR_DIGITS + "]{4}-[" + OCR_DIGITS + "]{2}-[" + OCR_DIGITS + "]{2}|[" + OCR_DIGITS + "]{2}[./-][" + OCR_DIGITS + "]{2}[./-][" + OCR_DIGITS + "]{2,4})"
    );
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yy"),
        DateTimeFormatter.ofPattern("dd/MM/yy"),
        DateTimeFormatter.ofPattern("dd-MM-yy")
    );

    public List<ReceiptOcrFieldNormalizationResult> findDateCandidates(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        Matcher matcher = DATE_TOKEN_PATTERN.matcher(text);
        List<ReceiptOcrFieldNormalizationResult> candidates = new ArrayList<>();
        while (matcher.find()) {
            normalizeDate(matcher.group(1)).ifPresent(candidates::add);
        }
        return List.copyOf(candidates);
    }

    public Optional<ReceiptOcrFieldNormalizationResult> normalizeDate(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return Optional.empty();
        }

        LinkedHashSet<String> actions = new LinkedHashSet<>();
        String normalized = fixNumericOcrDigits(rawValue.trim());
        if (!normalized.equals(rawValue.trim())) {
            actions.add("date_ocr_digit_correction");
        }

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate date = LocalDate.parse(normalized, formatter);
                if (normalized.matches("\\d{2}[./-]\\d{2}[./-]\\d{2}$")) {
                    date = date.withYear(adjustTwoDigitYear(date.getYear()));
                    actions.add("date_two_digit_year_expanded");
                }
                if (!date.toString().equals(normalized)) {
                    actions.add("date_iso_normalized");
                }
                return Optional.of(new ReceiptOcrFieldNormalizationResult(rawValue, date.toString(), List.copyOf(actions)));
            } catch (DateTimeParseException ignored) {
                // Try the next known receipt date format.
            }
        }
        return Optional.empty();
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

    private int adjustTwoDigitYear(int parsedYear) {
        return parsedYear < 100 ? 2000 + parsedYear : parsedYear;
    }
}
