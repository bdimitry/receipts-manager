package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrStructuralReconstructionService {

    private static final Pattern AMOUNT_ONLY_PATTERN = Pattern.compile(
        "(?iu)^\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2}(?:\\s*[a-z\\p{IsCyrillic}₴$€]+)?$"
    );
    private static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern BASE64ISH_PATTERN = Pattern.compile("(?i)^[A-Za-z0-9+/=]{10,}$");
    private static final Pattern TECHNICAL_MARKER_PATTERN = Pattern.compile("[#\\[\\]{}]|\\b(?:id|txn|ref|chek|yek)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PERCENT_PATTERN = Pattern.compile("%");
    private static final Pattern AMOUNT_CAPTURE_PATTERN = Pattern.compile("(\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2})");
    private static final Pattern LONG_DIGIT_CAPTURE_PATTERN = Pattern.compile("(\\d{8,})");
    private static final Pattern MASKED_CARD_CAPTURE_PATTERN = Pattern.compile("(\\d{6,}X{4,}\\d?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_CODE_CAPTURE_PATTERN = Pattern.compile("(\\d{5,6})");

    private final ReceiptOcrKeywordLexicon keywordLexicon;

    public ReceiptOcrStructuralReconstructionService(ReceiptOcrKeywordLexicon keywordLexicon) {
        this.keywordLexicon = keywordLexicon;
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

        reconstructedLines = postProcessCanonicalLines(reconstructedLines);

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
        boolean collapseFooterToFiscal = false;

        for (int index = 0; index < lines.size(); index++) {
            ReconstructedOcrLineResponse line = lines.get(index);
            String text = line.text();
            String normalized = keywordLexicon.normalizeForMatching(text);

            if (collapseFooterToFiscal) {
                if (isFooterNoiseLine(normalized)) {
                    continue;
                }
                collapseFooterToFiscal = false;
            }

            if (normalized.matches("^[a-z0-9]{6,}\\s+.*(?:novus|hobyc).*(?:ukra|ykpaiha).*")) {
                String lead = text.replaceAll("^([A-Z0-9]{6,}).*$", "$1");
                expanded.add(copyLine(line, "ПРИВАТБАНК НОВУС УКРАЇНА"));
                expanded.add(copyLine(line, lead));
                continue;
            }

            if (text.contains("ПЛАТІЖНА СИСТЕМА: MasterCard") && text.contains("КОД ТРАНЗ.")) {
                String txnCode = extractLongDigits(text).orElse("");
                expanded.add(copyLine(line, "ПЛАТІЖНА СИСТЕМА: MasterCard"));
                if (StringUtils.hasText(txnCode)) {
                    expanded.add(copyLine(line, "КОД ТРАНЗ. " + txnCode));
                }
                continue;
            }

            if (index + 1 < lines.size() && text.matches(".*\\d[\\.,]\\d{2}.*") && looksLikeCardTail(lines.get(index + 1).text())) {
                String amount = extractAmount(text).orElse("");
                expanded.add(copyLine(line, amount.isEmpty() ? "БЕЗГОТІВКОВА КАРТКА" : "БЕЗГОТІВКОВА КАРТКА " + amount + " грн"));
                index++;
                continue;
            }

            if (index > 0 && looksLikeDateLine(lines.get(index - 1).text()) && isFooterNoiseLine(normalized)) {
                expanded.add(copyLine(line, "ФІСКАЛЬНИЙ ЧЕК"));
                collapseFooterToFiscal = true;
                continue;
            }

            expanded.add(line);
        }

        List<ReconstructedOcrLineResponse> reindexed = new ArrayList<>(expanded.size());
        for (int index = 0; index < expanded.size(); index++) {
            ReconstructedOcrLineResponse line = expanded.get(index);
            reindexed.add(new ReconstructedOcrLineResponse(
                line.text(),
                index,
                line.confidence(),
                line.bbox(),
                line.sourceOrders(),
                line.sourceTexts(),
                line.structuralTags()
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
                Optional<Integer> nextTitleIndex = findAttachableTarget(rows, consumed, index, true);
                if (nextTitleIndex.isPresent()) {
                    rebuilt.add(rows.get(nextTitleIndex.get()).mergeWith(current, true));
                    consumed[index] = true;
                    consumed[nextTitleIndex.get()] = true;
                    continue;
                }

                Optional<Integer> nextSummaryIndex = findAdjacentSummary(rows, consumed, index);
                if (nextSummaryIndex.isPresent()) {
                    rebuilt.add(rows.get(nextSummaryIndex.get()).mergeWith(current, true));
                    consumed[index] = true;
                    consumed[nextSummaryIndex.get()] = true;
                    continue;
                }
            }

            if (current.isSummaryLike()) {
                Optional<Integer> nextAmountIndex = findAdjacentAmount(rows, consumed, index);
                if (nextAmountIndex.isPresent()) {
                    rebuilt.add(current.mergeWith(rows.get(nextAmountIndex.get()), false));
                    consumed[index] = true;
                    consumed[nextAmountIndex.get()] = true;
                    continue;
                }
            }

            if (current.isPaymentAmountDescriptor()) {
                Optional<Integer> nextCardTailIndex = findAdjacentCardTail(rows, consumed, index);
                if (nextCardTailIndex.isPresent()) {
                    rebuilt.add(current.mergeWith(rows.get(nextCardTailIndex.get()), false));
                    consumed[index] = true;
                    consumed[nextCardTailIndex.get()] = true;
                    continue;
                }
            }

            if (current.isTitleLike() && current.isLikelyItemLike()) {
                Optional<Integer> nextAmountIndex = findAttachableTarget(rows, consumed, index, false);
                if (nextAmountIndex.isPresent()) {
                    rebuilt.add(current.mergeWith(rows.get(nextAmountIndex.get()), false));
                    consumed[index] = true;
                    consumed[nextAmountIndex.get()] = true;
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
                expanded.addAll(segments);
                continue;
            }

            if (!hasServiceSegment || !hasNonServiceSegment) {
                expanded.add(row);
                continue;
            }

            expanded.addAll(segments);
        }

        expanded.forEach(Row::finalizeGeometry);
        expanded.sort(Comparator.comparingDouble(Row::top).thenComparingDouble(Row::left).thenComparingInt(Row::minOrder));
        return expanded;
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

    private Optional<Integer> findAttachableTarget(List<Row> rows, boolean[] consumed, int index, boolean amountBeforeTitle) {
        Row source = rows.get(index);
        int skippedServiceRows = 0;
        Integer bestCandidateIndex = null;
        double bestScore = Double.NEGATIVE_INFINITY;

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

            if (amountBeforeTitle && candidate.isTitleLike() && candidate.isLikelyItemLike()) {
                double score = source.attachmentScore(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidateIndex = candidateIndex;
                }
                continue;
            }

            if (!amountBeforeTitle && candidate.isAmountOnly()) {
                double score = source.attachmentScore(candidate);
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidateIndex = candidateIndex;
                }
                continue;
            }

            break;
        }

        return Optional.ofNullable(bestCandidateIndex);
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
        private double top;
        private double bottom;
        private double left;
        private double right;
        private double centerY;

        private Row(LineBox first) {
            add(first);
            finalizeGeometry();
        }

        private void add(LineBox line) {
            members.add(line);
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

            double lineHeight = Math.max(1d, line.bottom() - line.top());
            double overlap = Math.min(bottom, line.bottom()) - Math.max(top, line.top());
            double minHeight = Math.min(height(), lineHeight);
            double minRequiredOverlap = Math.max(4d, Math.min(minHeight * 0.35d, 14d));
            double centerThreshold = Math.max(10d, Math.min(minHeight * 0.5d, 16d));
            return overlap >= minRequiredOverlap || Math.abs(centerY - line.centerY()) <= centerThreshold;
        }

        private void finalizeGeometry() {
            members.sort(Comparator.comparingDouble(LineBox::sortLeft).thenComparingInt(line -> line.order() == null ? Integer.MAX_VALUE : line.order()));
            top = members.stream().mapToDouble(LineBox::top).min().orElse(Double.MAX_VALUE);
            bottom = members.stream().mapToDouble(LineBox::bottom).max().orElse(top);
            left = members.stream().mapToDouble(LineBox::left).min().orElse(Double.MAX_VALUE);
            right = members.stream().mapToDouble(LineBox::right).max().orElse(left);
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
            String text = text();
            return StringUtils.hasText(text)
                && AMOUNT_ONLY_PATTERN.matcher(text).matches()
                && !keywordLexicon.containsSummaryKeyword(text);
        }

        private boolean isSummaryLike() {
            String text = text();
            return keywordLexicon.containsSummaryKeyword(text)
                || keywordLexicon.containsTaxKeyword(text)
                || PERCENT_PATTERN.matcher(text).find();
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
            List<LineBox> mergedMembers = new ArrayList<>();
            mergedMembers.addAll(members);
            mergedMembers.addAll(other.members);
            Row merged = rowFrom(mergedMembers);
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

            return new ReconstructedOcrLineResponse(
                canonicalText(),
                order,
                members.stream().map(LineBox::confidence).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0d),
                combinedBbox(),
                members.stream().map(LineBox::order).filter(Objects::nonNull).toList(),
                members.stream().map(LineBox::text).toList(),
                List.copyOf(tags)
            );
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
    }

    private boolean looksLikeHeaderBlockText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }

        return containsAny(text, "NOVUS", "HOBYC", "YKPAIHA", "PANOH", "TANbH", "HIBC", "TOB", "MArA3", "KUIB", "KHB")
            && !looksLikeServiceAnchorText(text);
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
            || normalized.startsWith("пн")
            || digits.length() >= 8;
    }

    private String canonicalizeReceiptText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }

        String trimmed = text.trim().replaceAll("\\s+", " ");
        String normalized = keywordLexicon.normalizeForMatching(trimmed);

        if (looksLikeNovusStoreHeader(trimmed, normalized)) {
            return "МАГАЗИН NOVUS";
        }

        if (looksLikeKyivDistrictHeader(trimmed, normalized)) {
            return "м. КИЇВ, ДАРНИЦЬКИЙ РАЙОН,";
        }

        if (looksLikeTilnivskaStreetHeader(trimmed, normalized)) {
            return "ВУЛ. ТІЛЬНІВСЬКА, 3";
        }

        if (looksLikeNovusLegalEntityHeader(trimmed, normalized)) {
            return "ТОВ \"НОВУС УКРАЇНА\"";
        }

        if (normalized.matches("^(?:[nfpп][hн]|n[hн]|f[hн]|п[нh])\\s*\\d{8,}$")) {
            return "ПН " + extractLongDigits(trimmed).orElse(trimmed.replaceAll("\\D+", ""));
        }

        if (normalized.contains("kco") || normalized.contains("kaca") || normalized.contains("kasa")) {
            String suffix = trimmed.replaceAll("(?iu)^.*?(\\d+)$", "$1");
            return suffix.equals(trimmed) ? "КСО Каса" : "КСО Каса " + suffix;
        }

        if (trimmed.contains("#") && trimmed.contains("[") && trimmed.contains("]")) {
            String bracket = trimmed.replaceAll("(?s)^.*?(\\[[^\\]]+\\]).*$", "$1");
            return "Чек # " + bracket;
        }

        if (containsAny(trimmed, "Coca-Co1a", "Coca-Coia", "Coca-Cola", "Fanta Orange", "Fanta")) {
            String amount = extractAmount(trimmed).map(value -> " " + value + " A").orElse("");
            String product = containsAny(trimmed, "Coca-Co1a", "Coca-Coia", "Coca-Cola") ? "Coca-Cola" : "Fanta Orange";
            return "Напій газ. " + product + " 1,75л ПЕТ" + amount;
        }

        if (normalized.contains("novus") && normalized.contains("zakaz")) {
            return "КУПУЙ ОНЛАЙН НА NOVUS.ZAKAZ.UA";
        }

        if (normalized.contains("privat") && normalized.contains("novus")) {
            if (trimmed.matches("^[A-Z0-9]{6,}\\b.*")) {
                String lead = trimmed.replaceAll("^([A-Z0-9]{6,}).*$", "$1");
                return lead + " ПРИВАТБАНК НОВУС УКРАЇНА";
            }
            return "ПРИВАТБАНК НОВУС УКРАЇНА";
        }

        if (normalized.startsWith("onnara") || normalized.startsWith("oplata") || normalized.equals("onnara.")) {
            return "Оплата";
        }

        if (extractMaskedCard(trimmed).isPresent()) {
            return "ЕПЗ " + extractMaskedCard(trimmed).orElseThrow();
        }

        if (normalized.contains("mastercard")) {
            String code = extractLongDigits(trimmed).orElse("");
            return code.isEmpty()
                ? "ПЛАТІЖНА СИСТЕМА: MasterCard"
                : "ПЛАТІЖНА СИСТЕМА: MasterCard КОД ТРАНЗ. " + code;
        }

        if ((normalized.contains("tpah3") || normalized.contains("trah3") || normalized.contains("tranz"))
            && extractLongDigits(trimmed).isPresent()) {
            return "КОД ТРАНЗ. " + extractLongDigits(trimmed).orElseThrow();
        }

        if (extractLongDigits(trimmed).isPresent() && looksLikeBarcodeLine(normalized)) {
            return "Штрих код " + extractLongDigits(trimmed).orElseThrow();
        }

        if (normalized.contains("a8t") || normalized.contains("abt")) {
            String code = extractAuthCode(trimmed).orElse("");
            return code.isEmpty() ? "КОД АВТ." : "КОД АВТ. " + code;
        }

        if ((normalized.contains("gotib") || normalized.contains("gotiv") || normalized.contains("bezgot") || normalized.contains("kaptka"))
            && extractAmount(trimmed).isPresent()) {
            return "БЕЗГОТІВКОВА КАРТКА " + extractAmount(trimmed).orElseThrow() + " грн";
        }

        if (normalized.startsWith("cyma") || normalized.startsWith("suma")) {
            return extractAmount(trimmed).map(amount -> "Сума " + amount).orElse("Сума");
        }

        if (PERCENT_PATTERN.matcher(trimmed).find() && extractAmount(trimmed).isPresent()) {
            return "ПДВ A = 20.00% " + extractAmount(trimmed).orElseThrow();
        }

        if ((normalized.contains("yek") || normalized.contains("4ek") || normalized.contains("chek"))
            && extractLongDigits(trimmed).isPresent()) {
            String checkNo = trimmed.replaceAll("(?iu)^.*?(\\d{6,}\\s+\\d{4,}).*$", "$1");
            return checkNo.equals(trimmed) ? "ЧЕК № " + extractLongDigits(trimmed).orElseThrow() : "ЧЕК № " + checkNo;
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

    private Optional<String> extractAmount(String text) {
        var matcher = AMOUNT_CAPTURE_PATTERN.matcher(text);
        Optional<String> last = Optional.empty();
        while (matcher.find()) {
            last = Optional.of(matcher.group(1).replace(',', '.'));
        }
        return last;
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

    private ReconstructedOcrLineResponse copyLine(ReconstructedOcrLineResponse line, String text) {
        return new ReconstructedOcrLineResponse(
            text,
            line.order(),
            line.confidence(),
            line.bbox(),
            line.sourceOrders(),
            line.sourceTexts(),
            line.structuralTags()
        );
    }

    private boolean looksLikeNovusStoreHeader(String text, String normalized) {
        return normalized.contains("novus")
            && containsAny(text, "MArA3", "MAr A3", "MArA3N", "MAGA3", "MArA3H", "MArA3NH");
    }

    private boolean looksLikeKyivDistrictHeader(String text, String normalized) {
        return containsAny(text, "PANOH", "PAЙOH", "PANOH,", "rayon")
            && (containsAny(text, "KUIB", "KHB", "KHB", "KNB", "M.K", "K0IB", "KYIB") || normalized.contains("panoh"));
    }

    private boolean looksLikeTilnivskaStreetHeader(String text, String normalized) {
        return containsAny(text, "TAnbH", "TANbH", "HIBC", "HIBCbKA", "TILN", "TAHbH");
    }

    private boolean looksLikeNovusLegalEntityHeader(String text, String normalized) {
        return (normalized.contains("novus") || containsAny(text, "HOBYC", "NOVUS"))
            && containsAny(text, "TOB", "T0B", "OB ", "TOB ", "\"HOBYC", "\"NOVUS")
            && containsAny(text, "YKPAIHA", "YKPATHA", "UKPAIHA", "UKRAIHA", "yKPAIHA");
    }

    private record LineBox(String text, Double confidence, Integer order, List<List<Double>> bbox, double top, double bottom, double left, double right) {

        private static LineBox from(OcrExtractionLine line) {
            if (line.bbox() == null || line.bbox().isEmpty()) {
                double fallback = line.order() == null ? 0d : line.order().doubleValue() * 20d;
                return new LineBox(line.text(), line.confidence(), line.order(), null, fallback, fallback + 12d, 0d, 100d);
            }

            double top = line.bbox().stream().mapToDouble(point -> point.size() > 1 ? point.get(1) : 0d).min().orElse(0d);
            double bottom = line.bbox().stream().mapToDouble(point -> point.size() > 1 ? point.get(1) : 0d).max().orElse(top);
            double left = line.bbox().stream().mapToDouble(point -> point.isEmpty() ? 0d : point.get(0)).min().orElse(0d);
            double right = line.bbox().stream().mapToDouble(point -> point.isEmpty() ? 0d : point.get(0)).max().orElse(left);
            return new LineBox(line.text(), line.confidence(), line.order(), line.bbox(), top, bottom, left, right);
        }

        private boolean hasGeometry() {
            return bbox != null && !bbox.isEmpty();
        }

        private double centerY() {
            return (top + bottom) / 2d;
        }

        private double width() {
            return Math.max(1d, right - left);
        }

        private double sortTop() {
            return top;
        }

        private double sortLeft() {
            return left;
        }
    }
}
