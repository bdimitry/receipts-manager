package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.dto.OcrLineGeometryResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrStructuralReconstructionService {

    private static final Pattern AMOUNT_ONLY_PATTERN = Pattern.compile(
        "(?iu)^\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,:]\\d{2}(?:\\s*[a-z\\p{IsCyrillic}\\u20B4$\\u20AC\\u20BD]+)?$"
    );
    private static final Pattern MEASURE_ONLY_PATTERN = Pattern.compile(
        "(?iu)^(?:[a-z\\p{IsCyrillic}]{0,3}\\s*)?\\d{1,4}(?:[\\.,]\\d{1,3})?\\s*[a-z\\p{IsCyrillic}]{1,4}$"
    );
    private static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern BASE64ISH_PATTERN = Pattern.compile("(?i)^[A-Za-z0-9+/=]{10,}$");
    private static final Pattern TECHNICAL_MARKER_PATTERN = Pattern.compile("[#\\[\\]{}]|\\b(?:id|txn|ref|chek|yek)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PERCENT_PATTERN = Pattern.compile("%");
    private static final Pattern AMOUNT_CAPTURE_PATTERN = Pattern.compile("(\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,:]\\d{2})");
    private static final Pattern LONG_DIGIT_CAPTURE_PATTERN = Pattern.compile("(\\d{8,})");
    private static final Pattern MASKED_CARD_CAPTURE_PATTERN = Pattern.compile("(\\d{6,}X{4,}\\d?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_CODE_CAPTURE_PATTERN = Pattern.compile("(\\d{5,6})");
    private static final String CANONICAL_TAX_ID = "\u041F\u041D";
    private static final String CANONICAL_CASH_DESK = "\u041A\u0421\u041E \u041A\u0430\u0441\u0430";
    private static final String CANONICAL_CHECK_LABEL = "\u0427\u0435\u043A #";
    private static final String CANONICAL_PAYMENT_LABEL = "\u041E\u043F\u043B\u0430\u0442\u0430";
    private static final String CANONICAL_EPZ_LABEL = "\u0415\u041F\u0417";
    private static final String CANONICAL_PAYMENT_SYSTEM_PREFIX = "\u041F\u041B\u0410\u0422\u0406\u0416\u041D\u0410 \u0421\u0418\u0421\u0422\u0415\u041C\u0410: ";
    private static final String CANONICAL_TRANSACTION_CODE_LABEL = "\u041A\u041E\u0414 \u0422\u0420\u0410\u041D\u0417.";
    private static final String CANONICAL_AUTH_CODE_LABEL = "\u041A\u041E\u0414 \u0410\u0412\u0422.";
    private static final String CANONICAL_BARCODE_LABEL = "\u0428\u0442\u0440\u0438\u0445 \u043A\u043E\u0434";
    private static final String CANONICAL_NON_CASH_CARD_LABEL = "\u0411\u0415\u0417\u0413\u041E\u0422\u0406\u0412\u041A\u041E\u0412\u0410 \u041A\u0410\u0420\u0422\u041A\u0410";
    private static final String CANONICAL_TOTAL_LABEL = "\u0421\u0443\u043C\u0430";
    private static final String CANONICAL_TAX_LABEL = "\u041F\u0414\u0412 A = ";
    private static final String CANONICAL_CHECK_NUMBER_LABEL = "\u0427\u0415\u041A \u2116";

    private final ReceiptOcrKeywordLexicon keywordLexicon;
    private final ReceiptOcrDocumentZoneClassifier documentZoneClassifier;

    @Autowired
    public ReceiptOcrStructuralReconstructionService(
        ReceiptOcrKeywordLexicon keywordLexicon,
        ReceiptOcrDocumentZoneClassifier documentZoneClassifier
    ) {
        this.keywordLexicon = keywordLexicon;
        this.documentZoneClassifier = documentZoneClassifier;
    }

    public ReceiptOcrStructuralReconstructionService(ReceiptOcrKeywordLexicon keywordLexicon) {
        this(keywordLexicon, new ReceiptOcrDocumentZoneClassifier(keywordLexicon));
    }

    public ReconstructedOcrDocument reconstruct(OcrExtractionResult extractionResult) {
        if (extractionResult == null) {
            return new ReconstructedOcrDocument(null, List.of(), List.of(), "");
        }

        List<OcrExtractionLine> originalLines = extractionResult.lines() == null ? List.of() : extractionResult.lines();
        if (originalLines.isEmpty()) {
            return new ReconstructedOcrDocument(extractionResult.rawText(), List.of(), List.of(), "");
        }

        List<Row> rawRows = splitMixedRows(clusterRows(originalLines));
        List<Row> reconstructedRows = rebuildRows(rawRows);
        List<ReconstructedOcrLineResponse> reconstructedLines = new ArrayList<>(reconstructedRows.size());

        for (int index = 0; index < reconstructedRows.size(); index++) {
            reconstructedLines.add(reconstructedRows.get(index).toResponse(index));
        }

        reconstructedLines = documentZoneClassifier.classify(postProcessCanonicalLines(reconstructedLines));

        String reconstructedText = reconstructedLines.stream()
            .map(ReconstructedOcrLineResponse::text)
            .filter(StringUtils::hasText)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

        return new ReconstructedOcrDocument(
            extractionResult.rawText(),
            originalLines,
            List.copyOf(reconstructedLines),
            reconstructedText
        );
    }

    private List<ReconstructedOcrLineResponse> postProcessCanonicalLines(List<ReconstructedOcrLineResponse> lines) {
        if (lines.isEmpty()) {
            return lines;
        }

        List<ReconstructedOcrLineResponse> expanded = new ArrayList<>();
        boolean afterDateFooter = false;

        for (int index = 0; index < lines.size(); index++) {
            ReconstructedOcrLineResponse line = lines.get(index);
            String text = line.text();
            String normalized = keywordLexicon.normalizeForMatching(text);

            Optional<List<String>> headerAmountLeakSplit = splitHeaderAmountLeak(text, normalized);
            if (headerAmountLeakSplit.isPresent()) {
                for (String splitText : headerAmountLeakSplit.orElseThrow()) {
                    expanded.add(copyLine(line, splitText, "split_canonical_header_amount"));
                }
                continue;
            }

            Optional<ServiceLeadSplit> serviceLeadSplit = extractLeadingTechnicalServiceSplit(text, normalized);
            if (serviceLeadSplit.isPresent()) {
                ServiceLeadSplit split = serviceLeadSplit.orElseThrow();
                expanded.add(copyLine(line, split.serviceText(), "split_service_lead"));
                expanded.add(copyLine(line, split.leadToken(), "split_service_lead"));
                continue;
            }

            Optional<List<String>> combinedServiceSplit = splitCombinedServiceLine(text, normalized);
            if (combinedServiceSplit.isPresent()) {
                for (String splitText : combinedServiceSplit.orElseThrow()) {
                    expanded.add(copyLine(line, splitText, "split_service_payment"));
                }
                continue;
            }

            if (index + 1 < lines.size()
                && extractAmount(text).isPresent()
                && looksLikeCardTail(lines.get(index + 1).text())
                && looksLikePaymentDescriptor(normalized, text)) {
                expanded.add(copyLine(line, text + " " + lines.get(index + 1).text(), "paired_payment_card"));
                index++;
                afterDateFooter = false;
                continue;
            }

            if (looksLikeCardTail(text)
                && !expanded.isEmpty()
                && looksLikePaymentDescriptor(
                    keywordLexicon.normalizeForMatching(expanded.getLast().text()),
                    expanded.getLast().text()
                )) {
                continue;
            }

            if (looksLikeStandalonePaymentLabel(text, normalized)) {
                expanded.add(copyLine(line, CANONICAL_PAYMENT_LABEL));
                afterDateFooter = false;
                continue;
            }

            if (looksLikeDateLine(text)) {
                expanded.add(line);
                afterDateFooter = true;
                continue;
            }

            if ((index > 0 && looksLikeDateLine(lines.get(index - 1).text()) && isFooterNoiseLine(normalized))
                || (afterDateFooter && isFooterNoiseLine(normalized))) {
                continue;
            }

            expanded.add(line);
            afterDateFooter = false;
        }

        List<ReconstructedOcrLineResponse> reindexed = new ArrayList<>(expanded.size());
        for (int index = 0; index < expanded.size(); index++) {
            ReconstructedOcrLineResponse line = expanded.get(index);
            reindexed.add(new ReconstructedOcrLineResponse(
                canonicalizeReceiptText(line.text()),
                index,
                line.confidence(),
                line.bbox(),
                line.geometry(),
                line.documentZone(),
                line.documentZoneReasons(),
                line.sourceOrders(),
                line.sourceTexts(),
                line.structuralTags(),
                line.reconstructionActions()
            ));
        }
        return reindexed;
    }

    private List<Row> clusterRows(List<OcrExtractionLine> lines) {
        List<LineBox> sorted = lines.stream()
            .map(LineBox::from)
            .sorted(Comparator
                .comparingDouble(LineBox::sortTop)
                .thenComparingDouble(LineBox::sortLeft)
                .thenComparingInt(line -> line.order() == null ? Integer.MAX_VALUE : line.order()))
            .toList();

        List<Row> rows = new ArrayList<>();
        for (LineBox line : sorted) {
            Row matchingRow = rows.stream()
                .filter(row -> row.accepts(line))
                .min(Comparator.comparingDouble(row -> Math.abs(row.centerY() - line.centerY())))
                .orElse(null);
            if (matchingRow == null) {
                rows.add(new Row(line));
            } else {
                matchingRow.add(line);
            }
        }

        rows.forEach(Row::finalizeGeometry);
        rows.sort(Comparator.comparingDouble(Row::top).thenComparingDouble(Row::left).thenComparingInt(Row::minOrder));
        return rows;
    }

    private List<Row> rebuildRows(List<Row> rows) {
        List<Row> rebuilt = new ArrayList<>();
        boolean[] consumed = new boolean[rows.size()];

        for (int index = 0; index < rows.size(); index++) {
            if (consumed[index]) {
                continue;
            }

            Row current = rows.get(index);
            if (current.isAmountOnly()) {
                Optional<AttachmentMatch> nextTitle = findItemAttachment(rows, consumed, index, true);
                if (nextTitle.isPresent()) {
                    AttachmentMatch match = nextTitle.orElseThrow();
                    rebuilt.add(mergeItemAttachment(rows, current, match, true));
                    markConsumed(consumed, index, match);
                    continue;
                }

                Optional<Integer> nextSummaryIndex = findAdjacentSummary(rows, consumed, index);
                if (nextSummaryIndex.isPresent()) {
                    rebuilt.add(rows.get(nextSummaryIndex.get()).mergeWith(current, true, "paired_summary_amount"));
                    consumed[index] = true;
                    consumed[nextSummaryIndex.get()] = true;
                    continue;
                }
            }

            if (current.isSummaryLike()) {
                Optional<Integer> nextAmountIndex = findAdjacentAmount(rows, consumed, index);
                if (nextAmountIndex.isPresent()) {
                    rebuilt.add(current.mergeWith(rows.get(nextAmountIndex.get()), false, "paired_summary_amount"));
                    consumed[index] = true;
                    consumed[nextAmountIndex.get()] = true;
                    continue;
                }
            }

            if (current.isPaymentAmountDescriptor()) {
                Optional<Integer> nextCardTailIndex = findAdjacentCardTail(rows, consumed, index);
                if (nextCardTailIndex.isPresent()) {
                    rebuilt.add(current.mergeWith(rows.get(nextCardTailIndex.get()), false, "paired_payment_card"));
                    consumed[index] = true;
                    consumed[nextCardTailIndex.get()] = true;
                    continue;
                }
            }

            if (current.isTitleLike() && current.isLikelyItemLike()) {
                Optional<AttachmentMatch> nextAmount = findItemAttachment(rows, consumed, index, false);
                if (nextAmount.isPresent()) {
                    AttachmentMatch match = nextAmount.orElseThrow();
                    rebuilt.add(mergeItemAttachment(rows, current, match, false));
                    markConsumed(consumed, index, match);
                    continue;
                }
            }

            rebuilt.add(current);
            consumed[index] = true;
        }

        rebuilt.sort(Comparator.comparingDouble(Row::top).thenComparingDouble(Row::left).thenComparingInt(Row::minOrder));
        return rebuilt;
    }

    private List<Row> splitMixedRows(List<Row> rows) {
        List<Row> expanded = new ArrayList<>();
        for (Row row : rows) {
            if (row.members.size() < 2) {
                expanded.add(row);
                continue;
            }

            List<Row> segments = groupAdjacent(row.members);
            if (segments.size() <= 1) {
                expanded.add(row);
                continue;
            }

            boolean hasServiceSegment = segments.stream().anyMatch(Row::isServiceLike);
            boolean hasNonServiceSegment = segments.stream().anyMatch(segment -> !segment.isServiceLike());
            boolean hasAmountSegment = segments.stream().anyMatch(Row::isAmountOnly);

            if (hasAmountSegment) {
                tagSegments(segments, "split_amount_segment");
                expanded.addAll(segments);
                continue;
            }

            if (!hasServiceSegment || !hasNonServiceSegment) {
                expanded.add(row);
                continue;
            }

            tagSegments(segments, "split_service_segment");
            expanded.addAll(segments);
        }

        expanded.forEach(Row::finalizeGeometry);
        expanded.sort(Comparator.comparingDouble(Row::top).thenComparingDouble(Row::left).thenComparingInt(Row::minOrder));
        return expanded;
    }

    private void tagSegments(List<Row> segments, String action) {
        for (Row segment : segments) {
            segment.addAction(action);
            if (segment.isServiceLike()) {
                segment.addAction("isolated_service");
            }
        }
    }

    private List<Row> groupAdjacent(List<LineBox> fragments) {
        if (fragments.isEmpty()) {
            return List.of();
        }

        List<Row> grouped = new ArrayList<>();
        List<LineBox> currentGroup = new ArrayList<>();
        for (LineBox fragment : fragments) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(fragment);
                continue;
            }

            LineBox previous = currentGroup.getLast();
            double gap = fragment.left() - previous.right();
            double centerDelta = Math.abs(previous.centerY() - fragment.centerY());
            double rowThreshold = Math.max(12d, Math.min((previous.bottom() - previous.top()) * 0.5d, 24d));
            boolean serviceBoundary = isServiceFragment(previous) != isServiceFragment(fragment);
            if (!serviceBoundary
                && centerDelta <= rowThreshold
                && gap <= Math.max(24d, previous.width() * 0.35d)) {
                currentGroup.add(fragment);
                continue;
            }

            grouped.add(rowFrom(currentGroup));
            currentGroup = new ArrayList<>();
            currentGroup.add(fragment);
        }

        if (!currentGroup.isEmpty()) {
            grouped.add(rowFrom(currentGroup));
        }

        return grouped;
    }

    private Row rowFrom(List<LineBox> lines) {
        Row row = new Row(lines.getFirst());
        row.members.clear();
        row.members.addAll(lines);
        row.finalizeGeometry();
        return row;
    }

    private boolean isServiceFragment(LineBox line) {
        String text = line.text();
        if (!StringUtils.hasText(text)) {
            return false;
        }

        long digitCount = text.chars().filter(Character::isDigit).count();
        long letterCount = text.codePoints().filter(Character::isLetter).count();
        return keywordLexicon.containsPaymentKeyword(text)
            || keywordLexicon.containsBarcodeKeyword(text)
            || keywordLexicon.containsAccountKeyword(text)
            || LONG_DIGITS_PATTERN.matcher(text).find()
            || BASE64ISH_PATTERN.matcher(text.replace(" ", "")).matches()
            || (digitCount >= 10 && digitCount >= letterCount * 2);
    }

    private Row mergeItemAttachment(List<Row> rows, Row current, AttachmentMatch match, boolean amountBeforeTitle) {
        Row merged = amountBeforeTitle ? rows.get(match.targetIndex()) : current;
        for (int supportIndex : match.mergeSupportIndices()) {
            merged = merged.mergeWith(rows.get(supportIndex), false, "merged_support_fragment");
        }
        return amountBeforeTitle
            ? merged.mergeWith(current, true, "paired_amount_before_title")
            : merged.mergeWith(rows.get(match.targetIndex()), false, "paired_title_amount");
    }

    private void markConsumed(boolean[] consumed, int index, AttachmentMatch match) {
        consumed[index] = true;
        consumed[match.targetIndex()] = true;
        for (int supportIndex : match.mergeSupportIndices()) {
            consumed[supportIndex] = true;
        }
    }

    private Optional<AttachmentMatch> findItemAttachment(List<Row> rows, boolean[] consumed, int index, boolean amountBeforeTitle) {
        Row source = rows.get(index);
        int skippedServiceRows = 0;
        int skippedSupportRows = 0;
        int skippedNoiseRows = 0;
        int lastBridgeIndex = index;
        Integer bestCandidateIndex = null;
        List<Integer> bestSupportIndices = List.of();
        List<Integer> mergeSupportIndices = new ArrayList<>();
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int candidateIndex = index + 1; candidateIndex < rows.size() && candidateIndex <= index + 6; candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }

            Row candidate = rows.get(candidateIndex);
            Row bridge = rows.get(lastBridgeIndex);
            if (!source.isCloseTo(candidate) && !bridge.isCloseTo(candidate)) {
                break;
            }

            if (candidate.isServiceLike()) {
                skippedServiceRows++;
                if (skippedServiceRows > 2) {
                    break;
                }
                lastBridgeIndex = candidateIndex;
                continue;
            }

            if (candidate.isMeasureOnly()) {
                skippedSupportRows++;
                if (skippedSupportRows > 2) {
                    break;
                }
                mergeSupportIndices.add(candidateIndex);
                lastBridgeIndex = candidateIndex;
                continue;
            }

            if (candidate.isWeakBodyNoiseLike()) {
                skippedNoiseRows++;
                if (skippedNoiseRows > 2) {
                    break;
                }
                lastBridgeIndex = candidateIndex;
                continue;
            }

            boolean matchesTarget = amountBeforeTitle
                ? candidate.isTitleLike() && candidate.isLikelyItemLike()
                : candidate.isAmountOnly();
            if (matchesTarget) {
                double score = source.attachmentScore(candidate)
                    - (skippedServiceRows * 1.5d)
                    - (skippedSupportRows * 0.75d)
                    - (skippedNoiseRows * 0.75d);
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidateIndex = candidateIndex;
                    bestSupportIndices = List.copyOf(mergeSupportIndices);
                }
                continue;
            }

            break;
        }

        return bestCandidateIndex == null ? Optional.empty() : Optional.of(new AttachmentMatch(bestCandidateIndex, bestSupportIndices));
    }

    private Optional<Integer> findAdjacentSummary(List<Row> rows, boolean[] consumed, int index) {
        Row source = rows.get(index);
        int skippedServiceRows = 0;
        for (int candidateIndex = index + 1; candidateIndex < rows.size() && candidateIndex <= index + 3; candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }

            Row candidate = rows.get(candidateIndex);
            if (!source.isCloseTo(candidate)) {
                break;
            }

            if (candidate.isServiceLike()) {
                skippedServiceRows++;
                if (skippedServiceRows > 1) {
                    break;
                }
                continue;
            }

            if (candidate.isSummaryLike()) {
                return Optional.of(candidateIndex);
            }

            break;
        }
        return Optional.empty();
    }

    private Optional<Integer> findAdjacentAmount(List<Row> rows, boolean[] consumed, int index) {
        Row source = rows.get(index);
        for (int candidateIndex = index + 1; candidateIndex < rows.size() && candidateIndex <= index + 2; candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }
            Row candidate = rows.get(candidateIndex);
            if (!source.isCloseTo(candidate)) {
                break;
            }
            if (candidate.isServiceLike()) {
                continue;
            }
            if (candidate.isAmountOnly()) {
                return Optional.of(candidateIndex);
            }
            break;
        }
        return Optional.empty();
    }

    private Optional<Integer> findAdjacentCardTail(List<Row> rows, boolean[] consumed, int index) {
        Row source = rows.get(index);
        for (int candidateIndex = index + 1; candidateIndex < rows.size() && candidateIndex <= index + 2; candidateIndex++) {
            if (consumed[candidateIndex]) {
                continue;
            }
            Row candidate = rows.get(candidateIndex);
            if (!source.isCloseTo(candidate)) {
                break;
            }
            if (candidate.isCardTailLike()) {
                return Optional.of(candidateIndex);
            }
            if (!candidate.isServiceLike()) {
                break;
            }
        }
        return Optional.empty();
    }

    private final class Row {
        private final List<LineBox> members = new ArrayList<>();
        private final LinkedHashSet<String> reconstructionActions = new LinkedHashSet<>();
        private double top;
        private double bottom;
        private double left;
        private double right;
        private double centerX;
        private double centerY;

        private Row(LineBox first) {
            add(first);
            finalizeGeometry();
        }

        private void add(LineBox line) {
            members.add(line);
        }

        private void addAction(String action) {
            if (StringUtils.hasText(action)) {
                reconstructionActions.add(action);
            }
        }

        private boolean accepts(LineBox line) {
            if (members.isEmpty() || !hasGeometry() || !line.hasGeometry()) {
                return false;
            }

            if (shouldKeepSeparateFrom(line)) {
                double centerDelta = Math.abs(centerY - line.centerY());
                double leftDelta = Math.abs(left - line.left());
                if (centerDelta > 14d || leftDelta > 60d) {
                    return false;
                }
            }

            double lineHeight = Math.max(1d, line.height());
            double rowHeight = height();
            double overlap = Math.min(bottom, line.bottom()) - Math.max(top, line.top());
            double minHeight = Math.min(rowHeight, lineHeight);
            double maxHeight = Math.max(rowHeight, lineHeight);
            double overlapRatio = overlap <= 0d ? 0d : overlap / minHeight;
            double centerDelta = Math.abs(centerY - line.centerY());
            double centerThreshold = Math.max(8d, Math.min(maxHeight * 0.45d, 18d));
            boolean heightCompatible = maxHeight / Math.max(1d, minHeight) <= 2.4d || overlapRatio >= 0.65d;
            return heightCompatible && (overlapRatio >= 0.42d || centerDelta <= centerThreshold);
        }

        private void finalizeGeometry() {
            members.sort(Comparator.comparingDouble(LineBox::sortLeft).thenComparingInt(line -> line.order() == null ? Integer.MAX_VALUE : line.order()));
            top = members.stream().mapToDouble(LineBox::top).min().orElse(Double.MAX_VALUE);
            bottom = members.stream().mapToDouble(LineBox::bottom).max().orElse(top);
            left = members.stream().mapToDouble(LineBox::left).min().orElse(Double.MAX_VALUE);
            right = members.stream().mapToDouble(LineBox::right).max().orElse(left);
            centerX = (left + right) / 2d;
            centerY = (top + bottom) / 2d;
        }

        private boolean hasGeometry() {
            return members.stream().allMatch(LineBox::hasGeometry);
        }

        private double top() {
            return top;
        }

        private double left() {
            return left;
        }

        private double centerY() {
            return centerY;
        }

        private double height() {
            return Math.max(1d, bottom - top);
        }

        private double width() {
            return Math.max(1d, right - left);
        }

        private int minOrder() {
            return members.stream()
                .map(LineBox::order)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
        }

        private String text() {
            return members.stream()
                .map(LineBox::text)
                .filter(StringUtils::hasText)
                .reduce((leftText, rightText) -> leftText + " " + rightText)
                .orElse("")
                .trim();
        }

        private String canonicalText() {
            return canonicalizeReceiptText(text());
        }

        private boolean isAmountOnly() {
            String text = normalizeAmountTypography(text());
            return StringUtils.hasText(text)
                && AMOUNT_ONLY_PATTERN.matcher(text).matches()
                && !keywordLexicon.containsSummaryKeyword(text);
        }

        private boolean isSummaryLike() {
            String text = text();
            String normalized = keywordLexicon.normalizeForMatching(text);
            return keywordLexicon.containsSummaryKeyword(text)
                || keywordLexicon.containsTaxKeyword(text)
                || looksLikeTaxSummaryText(text, normalized);
        }

        private boolean isServiceLike() {
            String text = text();
            return keywordLexicon.containsPaymentKeyword(text)
                || keywordLexicon.containsBarcodeKeyword(text)
                || keywordLexicon.containsAccountKeyword(text)
                || LONG_DIGITS_PATTERN.matcher(text).find()
                || (!LETTER_PATTERN.matcher(text).find() && text.length() <= 2);
        }

        private boolean isTitleLike() {
            String text = text();
            return StringUtils.hasText(text)
                && LETTER_PATTERN.matcher(text).find()
                && !isSummaryLike()
                && !isServiceLike();
        }

        private boolean isLikelyItemLike() {
            String text = text();
            if (!isTitleLike() || !StringUtils.hasText(text)) {
                return false;
            }

            long digitCount = text.chars().filter(Character::isDigit).count();
            long letterCount = text.codePoints().filter(Character::isLetter).count();
            return !TECHNICAL_MARKER_PATTERN.matcher(text).find()
                && !LONG_DIGITS_PATTERN.matcher(text).find()
                && (letterCount >= 4)
                && digitCount <= Math.max(6L, letterCount);
        }

        private boolean isMeasureOnly() {
            String text = text();
            return StringUtils.hasText(text)
                && !isAmountOnly()
                && !isSummaryLike()
                && !isServiceLike()
                && MEASURE_ONLY_PATTERN.matcher(text.trim()).matches();
        }

        private boolean isWeakBodyNoiseLike() {
            String text = text();
            if (!StringUtils.hasText(text) || isAmountOnly() || isMeasureOnly() || isLikelyItemLike() || isSummaryLike() || isServiceLike()) {
                return false;
            }

            String normalized = keywordLexicon.normalizeForMatching(text);
            long letterCount = text.codePoints().filter(Character::isLetter).count();
            long digitCount = text.chars().filter(Character::isDigit).count();
            return keywordLexicon.containsPromoKeyword(text)
                || normalized.matches("(?iu)^[\\p{L}]{1,4}[\\p{Punct}]*$")
                || (letterCount > 0 && letterCount <= 4 && digitCount <= 4 && !keywordLexicon.extractMerchantAlias(text).isPresent());
        }

        private boolean isCardTailLike() {
            String normalized = keywordLexicon.normalizeForMatching(text());
            return normalized.contains("kaptka")
                || normalized.contains("kartka")
                || normalized.contains("kartka")
                || normalized.contains("kart");
        }

        private boolean shouldKeepSeparateFrom(LineBox line) {
            return (looksLikeHeaderBlockText(text()) && looksLikeServiceAnchorText(line.text()))
                || (looksLikeServiceAnchorText(text()) && looksLikeHeaderBlockText(line.text()));
        }

        private boolean isPaymentAmountDescriptor() {
            String normalized = keywordLexicon.normalizeForMatching(text());
            long letterCount = text().codePoints().filter(Character::isLetter).count();
            return AMOUNT_CAPTURE_PATTERN.matcher(text()).find()
                && (normalized.contains("gotib")
                || normalized.contains("gotiv")
                || normalized.contains("gotibo")
                || normalized.contains("bezgot")
                || normalized.contains("kart")
                || normalized.contains("ge3r")
                || normalized.contains("rotib")
                || normalized.contains("rph"))
                && letterCount >= 4;
        }

        private boolean isCloseTo(Row other) {
            if (!hasGeometry() || !other.hasGeometry()) {
                return Math.abs(minOrder() - other.minOrder()) <= 2;
            }

            double verticalGap = Math.max(0d, other.top - bottom);
            return verticalGap <= Math.max(18d, Math.max(height(), other.height()) * 1.35d);
        }

        private double attachmentScore(Row other) {
            if (!hasGeometry() || !other.hasGeometry()) {
                return -Math.abs(minOrder() - other.minOrder());
            }

            double overlap = Math.min(bottom, other.bottom) - Math.max(top, other.top);
            double verticalGap = Math.max(0d, other.top - bottom);
            return overlap - verticalGap - Math.abs(centerY - other.centerY) * 0.35d;
        }

        private Row mergeWith(Row other, boolean appendAmountAtEnd) {
            return mergeWith(other, appendAmountAtEnd, "merged");
        }

        private Row mergeWith(Row other, boolean appendAmountAtEnd, String action) {
            List<LineBox> mergedMembers = new ArrayList<>();
            mergedMembers.addAll(members);
            mergedMembers.addAll(other.members);
            Row merged = rowFrom(mergedMembers);
            merged.reconstructionActions.addAll(reconstructionActions);
            merged.reconstructionActions.addAll(other.reconstructionActions);
            merged.addAction("merged");
            merged.addAction(action);
            if (appendAmountAtEnd) {
                merged.members.sort(Comparator
                    .comparing((LineBox line) -> other.members.contains(line) ? 1 : 0)
                    .thenComparingDouble(LineBox::sortLeft)
                    .thenComparingInt(line -> line.order() == null ? Integer.MAX_VALUE : line.order()));
            }
            merged.finalizeGeometry();
            return merged;
        }

        private ReconstructedOcrLineResponse toResponse(int order) {
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            if (isAmountOnly()) {
                tags.add("amount_only");
            }
            if (isSummaryLike()) {
                tags.add("summary_like");
            }
            if (isServiceLike()) {
                tags.add("service_like");
            }
            if (isTitleLike()) {
                tags.add("title_like");
            }
            if (members.size() > 1) {
                tags.add("merged");
            }
            if (members.stream().anyMatch(line -> !line.hasGeometry())) {
                tags.add("geometry_inferred");
            }
            if (members.stream()
                .map(LineBox::confidence)
                .filter(Objects::nonNull)
                .anyMatch(confidence -> confidence < 0.7d)) {
                tags.add("low_confidence");
            }

            LinkedHashSet<String> actions = new LinkedHashSet<>(reconstructionActions);
            if (members.size() > 1) {
                actions.add("merged");
            }
            if (members.stream().anyMatch(line -> !line.hasGeometry())) {
                actions.add("geometry_inferred");
            }
            if (isReorderedByGeometry()) {
                actions.add("reordered_by_geometry");
            }
            if (tags.contains("low_confidence")) {
                actions.add("low_confidence_preserved");
            }

            return new ReconstructedOcrLineResponse(
                canonicalText(),
                order,
                members.stream().map(LineBox::confidence).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0d),
                combinedBbox(),
                geometry(),
                null,
                List.of(),
                members.stream().map(LineBox::order).filter(Objects::nonNull).toList(),
                members.stream().map(LineBox::text).toList(),
                List.copyOf(tags),
                List.copyOf(actions)
            );
        }

        private boolean isReorderedByGeometry() {
            List<Integer> orderedSourceOrders = members.stream()
                .map(LineBox::order)
                .filter(Objects::nonNull)
                .toList();
            if (orderedSourceOrders.size() < 2) {
                return false;
            }

            List<Integer> sortedSourceOrders = orderedSourceOrders.stream().sorted().toList();
            return !orderedSourceOrders.equals(sortedSourceOrders);
        }

        private List<List<Double>> combinedBbox() {
            if (!hasGeometry()) {
                return null;
            }
            return List.of(
                List.of(left, top),
                List.of(right, top),
                List.of(right, bottom),
                List.of(left, bottom)
            );
        }

        private OcrLineGeometryResponse geometry() {
            return new OcrLineGeometryResponse(
                left,
                right,
                top,
                bottom,
                centerX,
                centerY,
                width(),
                height()
            );
        }
    }

    private boolean looksLikeHeaderBlockText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }

        String normalized = keywordLexicon.normalizeForMatching(text);
        return !looksLikeServiceAnchorText(text)
            && (looksLikeStoreHeaderMarker(normalized)
            || looksLikeLegalEntityHeader(normalized)
            || keywordLexicon.extractMerchantAlias(text).isPresent()
            || keywordLexicon.containsHeaderKeyword(text));
    }

    private boolean looksLikeServiceAnchorText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }

        String normalized = keywordLexicon.normalizeForMatching(text);
        String digits = text.replaceAll("\\D+", "");
        return normalized.contains("kco")
            || normalized.contains("kaca")
            || normalized.contains("kasa")
            || (text.contains("#") && text.contains("[") && text.contains("]"))
            || normalized.startsWith("nh")
            || normalized.startsWith("pn") || normalized.startsWith("nh")
            || digits.length() >= 8;
    }

    private String canonicalizeReceiptText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        String trimmed = normalizeAmountTypography(stripTrailingCardTail(text.trim().replaceAll("\\s+", " ")));
        trimmed = trimmed.replaceAll("(\\d{2}\\.\\d{2}\\.\\d{4})(\\d{2}:\\d{2}(?::\\d{2})?)", "$1 $2");
        String normalized = keywordLexicon.normalizeForMatching(trimmed);

        if (normalized.matches("^(?:[nfpР В Р’В Р РЋРІР‚вЂќ][hР В Р’В Р В РІР‚В¦]|n[hР В Р’В Р В РІР‚В¦]|f[hР В Р’В Р В РІР‚В¦]|Р В Р’В Р РЋРІР‚вЂќ[Р В Р’В Р В РІР‚В¦h])\\s*\\d{8,}$")) {
            return CANONICAL_TAX_ID + " " + extractLongDigits(trimmed).orElse(trimmed.replaceAll("\\D+", ""));
        }

        if (normalized.contains("kco") || normalized.contains("kaca") || normalized.contains("kasa")) {
            String suffix = trimmed.replaceAll("(?iu)^.*?(\\d+)$", "$1");
            return suffix.equals(trimmed) ? CANONICAL_CASH_DESK : CANONICAL_CASH_DESK + " " + suffix;
        }

        if (trimmed.contains("#") && trimmed.contains("[") && trimmed.contains("]")) {
            String bracket = trimmed.replaceAll("(?s)^.*?(\\[[^\\]]+\\]).*$", "$1");
            return CANONICAL_CHECK_LABEL + " " + bracket;
        }

        if (normalized.contains("onnara")
            || normalized.contains("nnara")
            || normalized.contains("oplata")
            || normalized.contains("Р В Р’В Р РЋРІР‚СћР В Р’В Р РЋРІР‚вЂќР В Р’В Р вЂ™Р’В»Р В Р’В Р вЂ™Р’В°Р В Р Р‹Р Р†Р вЂљРЎв„ў")
            || trimmed.matches("(?iu).*[oР В Р’В Р РЋРІР‚Сћ0]n+n[aР В Р’В Р вЂ™Р’В°]r[aР В Р’В Р вЂ™Р’В°].*")) {
            return CANONICAL_PAYMENT_LABEL;
        }

        if (looksLikeStandalonePaymentLabel(trimmed, normalized)) {
            return CANONICAL_PAYMENT_LABEL;
        }

        if (extractMaskedCard(trimmed).isPresent()) {
            return CANONICAL_EPZ_LABEL + " " + extractMaskedCard(trimmed).orElseThrow();
        }

        if (normalized.contains("mastercard")) {
            String code = extractLongDigits(trimmed).orElse("");
            return code.isEmpty()
                ? CANONICAL_PAYMENT_SYSTEM_PREFIX + "MasterCard"
                : CANONICAL_PAYMENT_SYSTEM_PREFIX + "MasterCard " + CANONICAL_TRANSACTION_CODE_LABEL + " " + code;
        }

        if (normalized.contains("visa")) {
            String code = extractLongDigits(trimmed).orElse("");
            return code.isEmpty()
                ? CANONICAL_PAYMENT_SYSTEM_PREFIX + "Visa"
                : CANONICAL_PAYMENT_SYSTEM_PREFIX + "Visa " + CANONICAL_TRANSACTION_CODE_LABEL + " " + code;
        }

        if ((normalized.contains("tpah3") || normalized.contains("trah3") || normalized.contains("tranz"))
            && extractLongDigits(trimmed).isPresent()) {
            return CANONICAL_TRANSACTION_CODE_LABEL + " " + extractLongDigits(trimmed).orElseThrow();
        }

        if (extractLongDigits(trimmed).isPresent() && looksLikeBarcodeLine(normalized)) {
            return CANONICAL_BARCODE_LABEL + " " + extractLongDigits(trimmed).orElseThrow();
        }

        if (normalized.contains("a8t") || normalized.contains("abt")) {
            String code = extractAuthCode(trimmed).orElse("");
            return code.isEmpty() ? CANONICAL_AUTH_CODE_LABEL : CANONICAL_AUTH_CODE_LABEL + " " + code;
        }

        if (looksLikePaymentDescriptor(normalized, trimmed)
            && extractAmount(trimmed).isPresent()) {
            return CANONICAL_NON_CASH_CARD_LABEL + " " + extractAmount(trimmed).orElseThrow() + " \u0433\u0440\u043D";
        }

        if (normalized.startsWith("cyma") || normalized.startsWith("suma")) {
            return extractAmount(trimmed)
                .map(amount -> CANONICAL_TOTAL_LABEL + " " + amount + canonicalCurrencySuffix(normalized))
                .orElse(CANONICAL_TOTAL_LABEL);
        }

        if (looksLikeTaxSummaryText(trimmed, normalized) && extractAmount(trimmed).isPresent()) {
            String rate = extractVatRate(trimmed).orElse("20.00%");
            return CANONICAL_TAX_LABEL + rate + " " + extractAmount(trimmed).orElseThrow();
        }

        if ((normalized.contains("yek") || normalized.contains("4ek") || normalized.contains("chek"))
            && extractLongDigits(trimmed).isPresent()) {
            String checkNo = trimmed.replaceAll("(?iu)^.*?(\\d{6,}\\s+\\d{4,}).*$", "$1");
            return checkNo.equals(trimmed)
                ? CANONICAL_CHECK_NUMBER_LABEL + " " + extractLongDigits(trimmed).orElseThrow()
                : CANONICAL_CHECK_NUMBER_LABEL + " " + checkNo;
        }

        return trimmed;
    }
    private boolean looksLikeBarcodeLine(String normalized) {
        return normalized.contains("wtphx")
            || normalized.contains("utphx")
            || normalized.contains("urp")
            || normalized.contains("koa")
            || normalized.contains("kod")
            || normalized.contains("barcode");
    }

    private boolean looksLikeTaxSummaryText(String text, String normalized) {
        if (!StringUtils.hasText(text) || extractAmount(text).isEmpty()) {
            return false;
        }

        return keywordLexicon.containsTaxKeyword(text)
            || normalized.startsWith("nab")
            || normalized.startsWith("nav")
            || normalized.startsWith("pab")
            || normalized.contains("pdv")
            || normalized.contains("Р В РЎвЂ”Р В РўвЂР В Р вЂ ");
    }

    private String normalizeAmountTypography(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replaceAll("(?<=\\d)[:;](?=\\d{2}(?:\\D|$))", ".");
    }

    private Optional<String> extractAmount(String text) {
        var matcher = AMOUNT_CAPTURE_PATTERN.matcher(normalizeAmountTypography(text));
        Optional<String> last = Optional.empty();
        while (matcher.find()) {
            last = Optional.of(matcher.group(1).replace(" ", "").replace("\u00A0", "").replace(',', '.').replace(':', '.'));
        }
        return last;
    }

    private String canonicalCurrencySuffix(String normalized) {
        return normalized.matches(".*(?:uah|rph|rpn|tph|toh|reh|teh).*")
            || normalized.contains("\u0433\u0440\u043D")
            || normalized.contains("\u20B4")
            ? " \u0433\u0440\u043D"
            : "";
    }

    private Optional<String> extractLongDigits(String text) {
        var matcher = LONG_DIGIT_CAPTURE_PATTERN.matcher(text);
        Optional<String> last = Optional.empty();
        while (matcher.find()) {
            last = Optional.of(matcher.group(1));
        }
        return last;
    }

    private Optional<String> extractMaskedCard(String text) {
        var matcher = MASKED_CARD_CAPTURE_PATTERN.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).toUpperCase()) : Optional.empty();
    }

    private Optional<String> extractAuthCode(String text) {
        var matcher = AUTH_CODE_CAPTURE_PATTERN.matcher(text);
        Optional<String> last = Optional.empty();
        while (matcher.find()) {
            last = Optional.of(matcher.group(1));
        }
        return last;
    }

    private boolean containsAny(String text, String... probes) {
        for (String probe : probes) {
            if (text.contains(probe)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCardTail(String text) {
        String normalized = keywordLexicon.normalizeForMatching(text);
        return normalized.contains("kaptka") || normalized.contains("kartka") || normalized.contains("kart");
    }

    private boolean looksLikeDateLine(String text) {
        return StringUtils.hasText(text) && text.matches(".*\\d{2}\\.\\d{2}\\.\\d{4}.*\\d{2}:\\d{2}:\\d{2}.*");
    }

    private boolean isFooterNoiseLine(String normalized) {
        return normalized.matches("(?i)^[a-z0-9+/=]{3,}$")
            || normalized.contains("edck")
            || normalized.contains("fhq")
            || normalized.contains("cn802")
            || normalized.contains("300104")
            || normalized.equals("dy")
            || normalized.contains("btck");
    }

    private ReconstructedOcrLineResponse copyLine(ReconstructedOcrLineResponse line, String text, String... extraActions) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (line.reconstructionActions() != null) {
            actions.addAll(line.reconstructionActions());
        }
        for (String action : extraActions) {
            if (StringUtils.hasText(action)) {
                actions.add(action);
            }
        }

        return new ReconstructedOcrLineResponse(
            text,
            line.order(),
            line.confidence(),
            line.bbox(),
            line.geometry(),
            line.documentZone(),
            line.documentZoneReasons(),
            line.sourceOrders(),
            line.sourceTexts(),
            line.structuralTags(),
            List.copyOf(actions)
        );
    }

    private boolean looksLikeStoreHeaderMarker(String normalized) {
        return normalized.contains("maga3")
            || normalized.contains("magaz")
            || normalized.contains("mara3")
            || normalized.contains("Р В Р’В Р РЋР’ВР В Р’В Р вЂ™Р’В°Р В Р’В Р РЋРІР‚вЂњР В Р’В Р вЂ™Р’В°Р В Р’В Р вЂ™Р’В·")
            || normalized.contains("store")
            || normalized.contains("market");
    }

    private boolean looksLikeLegalEntityHeader(String normalized) {
        return (normalized.contains("tob")
            || normalized.contains("t0b")
            || normalized.contains("Р В Р Р‹Р Р†Р вЂљРЎв„ўР В Р’В Р РЋРІР‚СћР В Р’В Р В РІР‚В ")
            || normalized.startsWith("\""))
            && (normalized.contains("ukrai")
            || normalized.contains("ykpaiha")
            || normalized.contains("ykpaiha")
            || normalized.contains("Р В Р Р‹Р РЋРІР‚СљР В Р’В Р РЋРІР‚СњР В Р Р‹Р В РІР‚С™Р В Р’В Р вЂ™Р’В°Р В Р Р‹Р Р†Р вЂљРІР‚Сњ"));
    }

    private boolean looksLikeBankMerchantLine(String normalized) {
        boolean privatLike = normalized.contains("privat")
            || normalized.contains("phbat")
            || normalized.contains("pr1vat");
        boolean countryLike = normalized.contains("ukpaiha")
            || normalized.contains("ukraiha")
            || normalized.contains("Р В Р Р‹Р РЋРІР‚СљР В Р’В Р РЋРІР‚СњР В Р Р‹Р В РІР‚С™Р В Р’В Р вЂ™Р’В°Р В Р Р‹Р Р†Р вЂљРІР‚Сњ")
            || normalized.contains("ukra");
        boolean bankLike = normalized.contains("bank")
            || normalized.contains("gahk")
            || normalized.contains("Р В Р’В Р вЂ™Р’В±Р В Р’В Р вЂ™Р’В°Р В Р’В Р В РІР‚В¦Р В Р’В Р РЋРІР‚Сњ");
        return privatLike && (countryLike || bankLike);
    }

    private Optional<String> extractVatRate(String text) {
        var matcher = Pattern.compile("(\\d{1,2}(?:[\\.,]\\d{1,2})?\\s*%)").matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).replace(" ", "").replace(',', '.')) : Optional.empty();
    }

    private Optional<ServiceLeadSplit> extractLeadingTechnicalServiceSplit(String text, String normalized) {
        if (!normalized.matches("^[a-z0-9]{6,}\\s+.*")) {
            return Optional.empty();
        }

        String lead = text.replaceAll("^([A-Z0-9]{6,}).*$", "$1");
        String serviceText = text.replaceFirst("^[A-Z0-9]{6,}\\s+", "").trim();
        if (!StringUtils.hasText(serviceText)) {
            return Optional.empty();
        }

        String serviceNormalized = keywordLexicon.normalizeForMatching(serviceText);
        boolean likelyService = keywordLexicon.containsPaymentKeyword(serviceText)
            || keywordLexicon.containsAccountKeyword(serviceText)
            || looksLikeBankMerchantLine(serviceNormalized);
        if (!likelyService) {
            return Optional.empty();
        }
        return Optional.of(new ServiceLeadSplit(canonicalizeReceiptText(serviceText), lead));
    }

    private Optional<List<String>> splitCombinedServiceLine(String text, String normalized) {
        List<String> split = new ArrayList<>();
        String paymentSystem = canonicalizePaymentSystemLabel(text, normalized);
        if (StringUtils.hasText(paymentSystem) && !paymentSystem.equals(text)) {
            split.add(paymentSystem);
        }

        Optional<String> transactionCode = extractTransactionCodeLabel(text, normalized);
        transactionCode.ifPresent(split::add);

        return split.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(split));
    }

    private Optional<List<String>> splitHeaderAmountLeak(String text, String normalized) {
        if (!(looksLikeHeaderBlockText(text) || looksLikeLegalEntityHeader(normalized))) {
            return Optional.empty();
        }

        var matcher = Pattern.compile(
            "(?iu)^(.*?\\p{L}.*?)\\s+(\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,:]\\d{2}(?:\\s*[a-z\\p{IsCyrillic}Р В Р’В Р В РІР‚В Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В РЎС›Р Р†Р вЂљР’В$Р В Р’В Р В РІР‚В Р В Р вЂ Р В РІР‚С™Р РЋРІвЂћСћР В РІР‚в„ўР вЂ™Р’В¬]+)?)$"
        ).matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String headerText = matcher.group(1).trim();
        String amountText = matcher.group(2).trim();
        if (!StringUtils.hasText(headerText) || !StringUtils.hasText(amountText) || headerText.length() < 6) {
            return Optional.empty();
        }

        return Optional.of(List.of(headerText, amountText));
    }

    private String canonicalizePaymentSystemLabel(String text, String normalized) {
        if (normalized.contains("mastercard")) {
            return CANONICAL_PAYMENT_SYSTEM_PREFIX + "MasterCard";
        }
        if (normalized.contains("visa")) {
            return CANONICAL_PAYMENT_SYSTEM_PREFIX + "Visa";
        }
        return text;
    }

    private Optional<String> extractTransactionCodeLabel(String text, String normalized) {
        if ((normalized.contains("tpah3")
            || normalized.contains("trah3")
            || normalized.contains("tranz")
            || normalized.contains("koat")
            || normalized.contains("kod")
            || normalized.contains("mastercard")
            || normalized.contains("visa"))
            && extractLongDigits(text).isPresent()) {
            return Optional.of(CANONICAL_TRANSACTION_CODE_LABEL + " " + extractLongDigits(text).orElseThrow());
        }
        return Optional.empty();
    }

    private boolean looksLikeStandalonePaymentLabel(String text, String normalized) {
        return normalized.contains("onnara")
            || normalized.contains("nnara")
            || normalized.contains("oplata")
            || normalized.contains("oplta")
            || normalized.contains("Р В Р’В Р РЋРІР‚СћР В Р’В Р РЋРІР‚вЂќР В Р’В Р вЂ™Р’В»Р В Р’В Р вЂ™Р’В°Р В Р Р‹Р Р†Р вЂљРЎв„ў")
            || text.matches("(?iu)^[oР В Р’В Р РЋРІР‚Сћ0]n+n[aР В Р’В Р вЂ™Р’В°]r[aР В Р’В Р вЂ™Р’В°][\\p{Punct}\\s]*$")
            || text.matches("(?iu)^op+l[aР В Р’В Р вЂ™Р’В°]t[aР В Р’В Р вЂ™Р’В°][\\p{Punct}\\s]*$");
    }

    private boolean looksLikePaymentDescriptor(String normalized, String text) {
        long letterCount = text.codePoints().filter(Character::isLetter).count();
        return (normalized.contains("gotib")
            || normalized.contains("gotiv")
            || normalized.contains("bezgot")
            || normalized.contains("Р В Р’В Р вЂ™Р’В±Р В Р’В Р вЂ™Р’ВµР В Р’В Р вЂ™Р’В·Р В Р’В Р РЋРІР‚вЂњР В Р’В Р РЋРІР‚СћР В Р Р‹Р Р†Р вЂљРЎв„ў")
            || normalized.contains("kaptka")
            || normalized.contains("kart")
            || normalized.contains("Р В Р’В Р РЋРІР‚СњР В Р’В Р вЂ™Р’В°Р В Р Р‹Р В РІР‚С™Р В Р Р‹Р Р†Р вЂљРЎв„ў")
            || normalized.contains("rph"))
            && letterCount >= 4;
    }

    private String stripTrailingCardTail(String text) {
        return text.replaceAll("(?iu)\\s+(?:kaptka|kartka|kartk[ao]?|Р В Р’В Р РЋРІР‚СњР В Р’В Р вЂ™Р’В°Р В Р Р‹Р В РІР‚С™Р В Р Р‹Р Р†Р вЂљРЎв„ўР В Р’В Р РЋРІР‚Сњ[Р В Р’В Р вЂ™Р’В°-Р В Р Р‹Р В Р РЏР В Р Р‹Р Р†Р вЂљРІР‚СљР В Р Р‹Р Р†Р вЂљРІР‚СњР В Р Р‹Р Р†Р вЂљРЎСљР В РЎС›Р Р†Р вЂљР’В]*)\\s*$", "");
    }

    private record ServiceLeadSplit(String serviceText, String leadToken) { }

    private record AttachmentMatch(int targetIndex, List<Integer> mergeSupportIndices) { }

    private record LineBox(
        String text,
        Double confidence,
        Integer order,
        List<List<Double>> bbox,
        OcrLineGeometryResponse geometry,
        boolean geometryInferred
    ) {

        private static LineBox from(OcrExtractionLine line) {
            if (line.bbox() == null || line.bbox().isEmpty()) {
                double fallback = line.order() == null ? 0d : line.order().doubleValue() * 20d;
                return new LineBox(
                    line.text(),
                    line.confidence(),
                    line.order(),
                    null,
                    geometry(0d, 100d, fallback, fallback + 12d),
                    true
                );
            }

            double top = line.bbox().stream().mapToDouble(point -> point.size() > 1 ? point.get(1) : 0d).min().orElse(0d);
            double bottom = line.bbox().stream().mapToDouble(point -> point.size() > 1 ? point.get(1) : 0d).max().orElse(top);
            double left = line.bbox().stream().mapToDouble(point -> point.isEmpty() ? 0d : point.get(0)).min().orElse(0d);
            double right = line.bbox().stream().mapToDouble(point -> point.isEmpty() ? 0d : point.get(0)).max().orElse(left);
            return new LineBox(line.text(), line.confidence(), line.order(), line.bbox(), geometry(left, right, top, bottom), false);
        }

        private static OcrLineGeometryResponse geometry(double left, double right, double top, double bottom) {
            double width = Math.max(1d, right - left);
            double height = Math.max(1d, bottom - top);
            return new OcrLineGeometryResponse(
                left,
                right,
                top,
                bottom,
                left + width / 2d,
                top + height / 2d,
                width,
                height
            );
        }

        private boolean hasGeometry() {
            return !geometryInferred && bbox != null && !bbox.isEmpty();
        }

        private double top() {
            return geometry.minY();
        }

        private double bottom() {
            return geometry.maxY();
        }

        private double left() {
            return geometry.minX();
        }

        private double right() {
            return geometry.maxX();
        }

        private double centerY() {
            return geometry.centerY();
        }

        private double width() {
            return geometry.width();
        }

        private double height() {
            return geometry.height();
        }

        private double sortTop() {
            return top();
        }

        private double sortLeft() {
            return left();
        }
    }
}








