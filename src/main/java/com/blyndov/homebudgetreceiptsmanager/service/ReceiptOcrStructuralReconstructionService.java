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

            if (current.isTitleLike()) {
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

            List<LineBox> serviceFragments = row.members.stream().filter(this::isServiceFragment).toList();
            List<LineBox> contentFragments = row.members.stream().filter(fragment -> !isServiceFragment(fragment)).toList();

            if (serviceFragments.isEmpty() || contentFragments.isEmpty()) {
                expanded.add(row);
                continue;
            }

            expanded.add(rowFrom(contentFragments));
            expanded.addAll(groupAdjacent(serviceFragments));
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
            if (gap <= Math.max(24d, previous.width() * 0.35d)) {
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
            || (digitCount >= 10 && digitCount >= letterCount * 2);
    }

    private Optional<Integer> findAttachableTarget(List<Row> rows, boolean[] consumed, int index, boolean amountBeforeTitle) {
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

            if (amountBeforeTitle && candidate.isTitleLike()) {
                return Optional.of(candidateIndex);
            }

            if (!amountBeforeTitle && candidate.isAmountOnly()) {
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

            double overlap = Math.min(bottom, line.bottom()) - Math.max(top, line.top());
            double threshold = Math.max(8d, Math.min(height() * 0.65d, 26d));
            return overlap >= 2d || Math.abs(centerY - line.centerY()) <= threshold;
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

        private boolean isAmountOnly() {
            String text = text();
            return StringUtils.hasText(text)
                && AMOUNT_ONLY_PATTERN.matcher(text).matches()
                && !keywordLexicon.containsSummaryKeyword(text);
        }

        private boolean isSummaryLike() {
            String text = text();
            return keywordLexicon.containsSummaryKeyword(text) || keywordLexicon.containsTaxKeyword(text);
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

        private boolean isCloseTo(Row other) {
            if (!hasGeometry() || !other.hasGeometry()) {
                return Math.abs(minOrder() - other.minOrder()) <= 2;
            }

            double verticalGap = Math.max(0d, other.top - bottom);
            return verticalGap <= Math.max(18d, Math.max(height(), other.height()) * 1.35d);
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
                text(),
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
