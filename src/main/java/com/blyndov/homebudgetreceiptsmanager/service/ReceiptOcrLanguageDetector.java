package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrLanguageDetector {

    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[\\p{IsCyrillic}]");
    private static final Pattern POLISH_DIACRITICS_PATTERN = Pattern.compile("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]");
    private static final Pattern GERMAN_DIACRITICS_PATTERN = Pattern.compile("[äöüßÄÖÜ]");
    private static final List<String> CYRILLIC_HINTS = List.of("грн", "сума", "сумма", "дата", "разом", "чек", "тов");
    private static final List<String> POLISH_HINTS = List.of("paragon", "sprzedaz", "sprzedaż", "razem", "kwota", "sklep", "gotowka", "gotówka", "nip");
    private static final List<String> GERMAN_HINTS = List.of("summe", "betrag", "gesamt", "rechnung", "markt", "kasse", "mwst", "datum");

    public DetectedOcrProfile detect(OcrExtractionResult extractionResult) {
        String sampleText = buildSampleText(extractionResult);
        if (!StringUtils.hasText(sampleText)) {
            return null;
        }

        if (looksCyrillic(sampleText)) {
            return new DetectedOcrProfile("cyrillic", "Detected Cyrillic script and local receipt vocabulary.");
        }

        if (looksPolish(sampleText)) {
            return new DetectedOcrProfile("polish", "Detected Polish diacritics or Polish receipt keywords.");
        }

        if (looksGerman(sampleText)) {
            return new DetectedOcrProfile("german", "Detected German diacritics or German receipt keywords.");
        }

        return null;
    }

    private String buildSampleText(OcrExtractionResult extractionResult) {
        if (extractionResult == null) {
            return "";
        }

        if (StringUtils.hasText(extractionResult.rawText())) {
            return extractionResult.rawText();
        }

        return extractionResult.lines() == null
            ? ""
            : extractionResult.lines().stream()
                .map(line -> line.text() == null ? "" : line.text())
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private boolean looksCyrillic(String sampleText) {
        return countMatches(CYRILLIC_PATTERN, sampleText) >= 3 || containsAny(sampleText, CYRILLIC_HINTS);
    }

    private boolean looksPolish(String sampleText) {
        return POLISH_DIACRITICS_PATTERN.matcher(sampleText).find() || containsAny(sampleText, POLISH_HINTS);
    }

    private boolean looksGerman(String sampleText) {
        return GERMAN_DIACRITICS_PATTERN.matcher(sampleText).find() || containsAny(sampleText, GERMAN_HINTS);
    }

    private boolean containsAny(String sampleText, List<String> hints) {
        String normalized = sampleText.toLowerCase(Locale.ROOT);
        return hints.stream().anyMatch(normalized::contains);
    }

    private int countMatches(Pattern pattern, String sampleText) {
        return (int) pattern.matcher(sampleText).results().count();
    }
}
