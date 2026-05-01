package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.OcrDocumentZoneType;
import com.blyndov.homebudgetreceiptsmanager.dto.OcrLineGeometryResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrDocumentZoneClassifier {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\b\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,:]\\d{2}\\b");
    private static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b");
    private static final Pattern MASKED_CARD_PATTERN = Pattern.compile("\\d{4,}X{3,}\\d*", Pattern.CASE_INSENSITIVE);

    private final ReceiptOcrKeywordLexicon keywordLexicon;

    public ReceiptOcrDocumentZoneClassifier(ReceiptOcrKeywordLexicon keywordLexicon) {
        this.keywordLexicon = keywordLexicon;
    }

    public List<ReconstructedOcrLineResponse> classify(List<ReconstructedOcrLineResponse> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        LayoutBounds bounds = LayoutBounds.from(lines);
        List<ReconstructedOcrLineResponse> classified = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            ReconstructedOcrLineResponse line = lines.get(index);
            ZoneDecision decision = classifyLine(line, index, lines.size(), bounds);
            classified.add(withZone(line, decision));
        }
        return List.copyOf(classified);
    }

    private ZoneDecision classifyLine(
        ReconstructedOcrLineResponse line,
        int index,
        int totalLines,
        LayoutBounds bounds
    ) {
        String text = line.text();
        if (!StringUtils.hasText(text)) {
            return new ZoneDecision(OcrDocumentZoneType.UNKNOWN, List.of("empty_text"));
        }

        String normalized = keywordLexicon.normalizeForMatching(text);
        double position = bounds.position(line, index, totalLines);
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        boolean topZone = position <= 0.22d;
        boolean bottomZone = position >= 0.82d;
        boolean bodyZone = position > 0.18d && position < 0.78d;
        boolean hasAmount = AMOUNT_PATTERN.matcher(text).find();
        boolean hasLetters = text.codePoints().anyMatch(Character::isLetter);
        boolean hasServiceTag = hasTag(line, "service_like");
        boolean hasSummaryTag = hasTag(line, "summary_like");
        boolean hasExplicitTotalMarker = looksLikeExplicitTotalLine(text, normalized);
        boolean hasPaymentMarker = looksLikePaymentLine(text, normalized);

        if (hasPaymentMarker && !hasExplicitTotalMarker) {
            reasons.add("payment_keyword");
            if (hasAmount) {
                reasons.add("amount_present");
            }
            return decision(OcrDocumentZoneType.PAYMENT, reasons);
        }

        if (looksLikeTotalLine(text, normalized, hasSummaryTag)) {
            reasons.add(hasSummaryTag ? "summary_tag" : "summary_keyword");
            if (bottomZone) {
                reasons.add("bottom_position");
            }
            return decision(OcrDocumentZoneType.TOTALS, reasons);
        }

        if (looksLikeServiceLine(text, normalized, hasServiceTag)) {
            reasons.add(hasServiceTag ? "service_tag" : "barcode_or_service_pattern");
            return decision(OcrDocumentZoneType.SERVICE, reasons);
        }

        if (looksLikeMetadataLine(text, normalized)) {
            reasons.add("metadata_pattern");
            return decision(OcrDocumentZoneType.METADATA, reasons);
        }

        if (topZone && looksLikeMerchantBlock(text, normalized)) {
            reasons.add("top_position");
            reasons.add("merchant_or_header_pattern");
            return decision(OcrDocumentZoneType.MERCHANT_BLOCK, reasons);
        }

        if (topZone) {
            reasons.add("top_position");
            return decision(OcrDocumentZoneType.HEADER, reasons);
        }

        if (bodyZone && hasLetters && !hasServiceTag) {
            reasons.add("body_position");
            reasons.add(hasAmount ? "item_amount_present" : "item_text_present");
            return decision(OcrDocumentZoneType.ITEMS, reasons);
        }

        if (bottomZone) {
            reasons.add("bottom_position");
            return decision(OcrDocumentZoneType.FOOTER, reasons);
        }

        reasons.add("no_strong_zone_signal");
        return decision(OcrDocumentZoneType.UNKNOWN, reasons);
    }

    private boolean looksLikeTotalLine(String text, String normalized, boolean hasSummaryTag) {
        return hasSummaryTag
            || keywordLexicon.containsTaxKeyword(text)
            || looksLikeExplicitTotalLine(text, normalized);
    }

    private boolean looksLikeExplicitTotalLine(String text, String normalized) {
        return keywordLexicon.containsTaxKeyword(text)
            || containsAny(normalized, "total", "subtotal", "amount", "balance", "sum", "suma", "cyma", "pdv")
            || containsAny(normalized, "\u0441\u0443\u043c\u0430", "\u0441\u0443\u043c\u043c\u0430", "\u0438\u0442\u043e\u0433\u043e", "\u0432\u0441\u044c\u043e\u0433\u043e", "\u0440\u0430\u0437\u043e\u043c");
    }

    private boolean looksLikePaymentLine(String text, String normalized) {
        return keywordLexicon.containsPaymentKeyword(text)
            || MASKED_CARD_PATTERN.matcher(text).find()
            || containsAny(normalized, "mastercard", "visa", "terminal", "transaction", "auth", "kart", "kaptk", "bezgot", "gotiv", "oplata");
    }

    private boolean looksLikeServiceLine(String text, String normalized, boolean hasServiceTag) {
        long digitCount = text.chars().filter(Character::isDigit).count();
        long letterCount = text.codePoints().filter(Character::isLetter).count();
        return keywordLexicon.containsBarcodeKeyword(text)
            || (LONG_DIGITS_PATTERN.matcher(text).find() && digitCount >= Math.max(letterCount * 2, 8))
            || (hasServiceTag && digitCount >= 8)
            || containsAny(normalized, "barcode", "ean", "wtphx", "utphx", "koa", "kod");
    }

    private boolean looksLikeMetadataLine(String text, String normalized) {
        String digits = text.replaceAll("\\D+", "");
        return DATE_PATTERN.matcher(text).find()
            || containsAny(normalized, "kco", "kaca", "kasa", "kassa", "check", "chek", "receipt")
            || containsAny(normalized, "\u043a\u0441\u043e", "\u043a\u0430\u0441\u0430", "\u043a\u0430\u0441\u0441\u0430", "\u0447\u0435\u043a")
            || normalized.startsWith("pn ")
            || normalized.startsWith("nh ")
            || normalized.startsWith("id ")
            || digits.length() >= 8 && containsAny(normalized, "pn", "nh", "edrpou");
    }

    private boolean looksLikeMerchantBlock(String text, String normalized) {
        return keywordLexicon.extractMerchantAlias(text).isPresent()
            || keywordLexicon.containsHeaderKeyword(text)
            || containsAny(normalized, "store", "market", "magaz", "maga3", "mara3", "tob", "llc", "ltd");
    }

    private boolean hasTag(ReconstructedOcrLineResponse line, String tag) {
        return line.structuralTags() != null && line.structuralTags().contains(tag);
    }

    private boolean containsAny(String normalized, String... probes) {
        for (String probe : probes) {
            if (normalized.contains(probe)) {
                return true;
            }
        }
        return false;
    }

    private ReconstructedOcrLineResponse withZone(ReconstructedOcrLineResponse line, ZoneDecision decision) {
        return new ReconstructedOcrLineResponse(
            line.text(),
            line.order(),
            line.confidence(),
            line.bbox(),
            line.geometry(),
            decision.type(),
            decision.reasons(),
            line.sourceOrders(),
            line.sourceTexts(),
            line.structuralTags(),
            line.reconstructionActions()
        );
    }

    private ZoneDecision decision(OcrDocumentZoneType type, LinkedHashSet<String> reasons) {
        return new ZoneDecision(type, List.copyOf(reasons));
    }

    private record ZoneDecision(OcrDocumentZoneType type, List<String> reasons) {
    }

    private record LayoutBounds(double minY, double maxY, boolean hasGeometry) {

        private static LayoutBounds from(List<ReconstructedOcrLineResponse> lines) {
            List<OcrLineGeometryResponse> geometries = lines.stream()
                .map(ReconstructedOcrLineResponse::geometry)
                .filter(geometry -> geometry != null)
                .toList();
            if (geometries.isEmpty()) {
                return new LayoutBounds(0d, 0d, false);
            }

            double minY = geometries.stream().mapToDouble(OcrLineGeometryResponse::minY).min().orElse(0d);
            double maxY = geometries.stream().mapToDouble(OcrLineGeometryResponse::maxY).max().orElse(minY);
            return new LayoutBounds(minY, maxY, maxY > minY);
        }

        private double position(ReconstructedOcrLineResponse line, int index, int totalLines) {
            if (!hasGeometry || line.geometry() == null) {
                return totalLines <= 1 ? 0d : (double) index / (double) (totalLines - 1);
            }

            double documentHeight = Math.max(1d, maxY - minY);
            return Math.max(0d, Math.min(1d, (line.geometry().centerY() - minY) / documentHeight));
        }
    }
}
