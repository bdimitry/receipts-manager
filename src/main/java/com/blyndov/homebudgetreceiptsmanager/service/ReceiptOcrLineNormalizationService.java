package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrLineNormalizationService {

    private static final String CYRILLIC_AND_LATIN = "A-Za-z\\p{IsCyrillic}";
    private static final Pattern LETTER_RE = Pattern.compile("[" + CYRILLIC_AND_LATIN + "]");
    private static final Pattern DIGIT_RE = Pattern.compile("\\d");
    private static final Pattern AMOUNT_RE = Pattern.compile("\\b\\d+[.,]\\d{2}\\b");
    private static final Pattern LONG_DIGITS_RE = Pattern.compile("\\d{10,}");
    private static final Pattern BARCODE_HINT_RE = Pattern.compile(
        "(barcode|ean|штрих|код)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern WORD_SEPARATOR_RE = Pattern.compile("(?<=[" + CYRILLIC_AND_LATIN + "])[.:;|](?=[" + CYRILLIC_AND_LATIN + "])");
    private static final Pattern PUNCT_SPACING_RE = Pattern.compile("\\s*([=:])\\s*");
    private static final Pattern MULTI_PUNCT_RE = Pattern.compile("([.,:;=\\-]){2,}");
    private static final Pattern MULTIPLIER_RE = Pattern.compile("(?:(?<=\\s)|(?<=\\d)|(?<=\\b))[xхХ×](?:(?=\\s)|(?=\\d)|(?=\\b))");
    private static final Pattern AMOUNT_SUFFIX_RE = Pattern.compile("(?<=\\d)\\s*[,.;:]+\\s*$");
    private static final Pattern TRAILING_EQUALS_RE = Pattern.compile("\\s+=\\s*$");
    private static final Pattern LEADING_EDGE_RE = Pattern.compile("^[\\s`'\".,;:|=_-]+");
    private static final Pattern TRAILING_EDGE_RE = Pattern.compile("[\\s`'\";:|=_-]+$");
    private static final Pattern NON_WORD_COMPACT_RE = Pattern.compile("[^0-9A-Za-z\\p{IsCyrillic}]+");
    private static final Pattern SERVICE_HINT_RE = Pattern.compile(
        "(receipt|cash|date|document|thank|total|subtotal|balance|sale|discount|special|sum|"
            + "дата|чек|каса|касса|документ|сума|разом|ціна|спец)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern HEADER_HINT_RE = Pattern.compile(
        "(receipt|cash|date|document|thank|store|market|дата|чек|документ|магазин|маркет)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public NormalizedOcrDocument normalizeDocument(OcrExtractionResult extractionResult) {
        String rawText = extractionResult == null ? null : extractionResult.rawText();
        List<NormalizedOcrLineResponse> normalizedLines = extractionResult == null
            ? List.of()
            : normalizeLines(extractionResult.lines());
        return buildDocument(rawText, normalizedLines);
    }

    public NormalizedOcrDocument normalizeRawTextDocument(String rawText) {
        return buildDocument(rawText, normalizeRawText(rawText));
    }

    public List<NormalizedOcrLineResponse> normalizeLines(List<OcrExtractionLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        List<OcrExtractionLine> orderedLines = lines.stream()
            .sorted(Comparator.comparing(line -> line.order() == null ? Integer.MAX_VALUE : line.order()))
            .toList();

        int totalLines = orderedLines.size();
        List<NormalizedOcrLineResponse> normalized = new ArrayList<>(totalLines);

        for (int index = 0; index < orderedLines.size(); index++) {
            OcrExtractionLine line = orderedLines.get(index);
            String normalizedText = normalizeText(line.text());
            List<String> tags = classify(normalizedText, index, totalLines);
            boolean ignored = tags.contains("noise") || tags.contains("barcode_like");
            normalized.add(
                new NormalizedOcrLineResponse(
                    line.text(),
                    normalizedText,
                    line.order() == null ? index : line.order(),
                    line.confidence(),
                    line.bbox(),
                    tags,
                    ignored
                )
            );
        }

        return normalized;
    }

    public List<NormalizedOcrLineResponse> normalizeRawText(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return List.of();
        }

        AtomicInteger order = new AtomicInteger();
        List<OcrExtractionLine> rawLines = rawText.lines()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(text -> new OcrExtractionLine(text, null, order.getAndIncrement(), null))
            .toList();
        return normalizeLines(rawLines);
    }

    private NormalizedOcrDocument buildDocument(String rawText, List<NormalizedOcrLineResponse> normalizedLines) {
        List<NormalizedOcrLineResponse> parserReadyLines = normalizedLines.stream()
            .filter(line -> !line.ignored())
            .filter(line -> StringUtils.hasText(line.normalizedText()))
            .toList();

        if (parserReadyLines.isEmpty()) {
            parserReadyLines = normalizedLines.stream()
                .filter(line -> StringUtils.hasText(line.normalizedText()))
                .toList();
        }

        String parserReadyText = parserReadyLines.stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .filter(StringUtils::hasText)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

        return new NormalizedOcrDocument(
            rawText,
            normalizedLines,
            parserReadyLines,
            parserReadyText
        );
    }

    private String normalizeText(String text) {
        String normalized = text == null ? "" : text.trim();
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = WORD_SEPARATOR_RE.matcher(normalized).replaceAll(" ");
        normalized = MULTIPLIER_RE.matcher(normalized).replaceAll("x");
        normalized = MULTI_PUNCT_RE.matcher(normalized).replaceAll(match -> String.valueOf(match.group(1).charAt(0)));
        normalized = normalized.replace(" ,", ",").replace(" .", ".").replace(" :", ":").replace(" ;", ";");
        normalized = PUNCT_SPACING_RE.matcher(normalized).replaceAll(match -> match.group(1) + " ");
        normalized = AMOUNT_SUFFIX_RE.matcher(normalized).replaceAll("");
        normalized = TRAILING_EQUALS_RE.matcher(normalized).replaceAll("");
        normalized = LEADING_EDGE_RE.matcher(normalized).replaceAll("");
        normalized = TRAILING_EDGE_RE.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private List<String> classify(String normalizedText, int order, int totalLines) {
        if (!StringUtils.hasText(normalizedText)) {
            return List.of("noise");
        }

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        int alphaCount = countMatches(LETTER_RE, normalizedText);
        int digitCount = countMatches(DIGIT_RE, normalizedText);
        boolean hasAmount = AMOUNT_RE.matcher(normalizedText).find();
        boolean hasLongDigits = LONG_DIGITS_RE.matcher(normalizedText).find();
        String compact = NON_WORD_COMPACT_RE.matcher(normalizedText).replaceAll("");

        if (alphaCount == 0 && digitCount < 3) {
            tags.add("noise");
        }

        if (normalizedText.length() <= 2 && !hasAmount) {
            tags.add("noise");
        }

        if (hasLongDigits && alphaCount < 8) {
            tags.add("barcode_like");
        }

        if (hasLongDigits && BARCODE_HINT_RE.matcher(normalizedText).find()) {
            tags.add("barcode_like");
        }

        if (!compact.isBlank() && compact.length() > 24 && digitCount >= Math.max(alphaCount * 2, 12)) {
            tags.add("barcode_like");
        }

        if (hasAmount) {
            tags.add("price_like");
        }

        if (SERVICE_HINT_RE.matcher(normalizedText).find()) {
            tags.add("service_like");
        }

        int headerWindow = Math.max(3, Math.min(totalLines, 4));
        if (order < headerWindow && (HEADER_HINT_RE.matcher(normalizedText).find() || (!hasAmount && alphaCount >= 3))) {
            tags.add("header_like");
        }

        if (!tags.contains("noise") && !tags.contains("barcode_like") && !tags.contains("header_like") && !tags.contains("service_like")) {
            tags.add("content_like");
        } else if (tags.contains("price_like") && !tags.contains("barcode_like") && !tags.contains("noise")) {
            tags.add("content_like");
        }

        return List.copyOf(tags);
    }

    private int countMatches(Pattern pattern, String value) {
        return (int) pattern.matcher(value).results().count();
    }
}
