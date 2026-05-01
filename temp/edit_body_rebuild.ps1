$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$rebuildRows = @"
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
"@
$helperBlock = @"
    private Row mergeItemAttachment(List<Row> rows, Row current, AttachmentMatch match, boolean amountBeforeTitle) {
        Row merged = amountBeforeTitle ? rows.get(match.targetIndex()) : current;
        for (int supportIndex : match.mergeSupportIndices()) {
            merged = merged.mergeWith(rows.get(supportIndex), false);
        }
        return amountBeforeTitle
            ? merged.mergeWith(current, true)
            : merged.mergeWith(rows.get(match.targetIndex()), false);
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
"@
$rebuildRegex = New-Object System.Text.RegularExpressions.Regex('(?s)    private List<Row> rebuildRows\(List<Row> rows\) \{.*?^    \}', [System.Text.RegularExpressions.RegexOptions]::Multiline)
$content = $rebuildRegex.Replace($content, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $rebuildRows.TrimEnd() }, 1)
$attachRegex = New-Object System.Text.RegularExpressions.Regex('(?s)    private Optional<Integer> findAttachableTarget\(List<Row> rows, boolean\[\] consumed, int index, boolean amountBeforeTitle\) \{.*?^    \}', [System.Text.RegularExpressions.RegexOptions]::Multiline)
$content = $attachRegex.Replace($content, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $helperBlock.TrimEnd() }, 1)
Set-Content -Path $path -Value $content
