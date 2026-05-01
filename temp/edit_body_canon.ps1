$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$content = $content.Replace('        String trimmed = stripTrailingCardTail(text.trim().replaceAll("\\s+", " "));', '        String trimmed = normalizeAmountTypography(stripTrailingCardTail(text.trim().replaceAll("\\s+", " ")));')
$content = $content.Replace(@"
        if (PERCENT_PATTERN.matcher(trimmed).find() && extractAmount(trimmed).isPresent()) {
            String rate = extractVatRate(trimmed).orElse("20.00%");
            return "ПДВ A = " + rate + " " + extractAmount(trimmed).orElseThrow();
        }
"@.Trim(), @"
        if (looksLikeTaxSummaryText(trimmed, normalized) && extractAmount(trimmed).isPresent()) {
            String rate = extractVatRate(trimmed).orElse("20.00%");
            return "ПДВ A = " + rate + " " + extractAmount(trimmed).orElseThrow();
        }
"@.Trim())
$content = $content.Replace(@"
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
            last = Optional.of(matcher.group(1).replace(" ", "").replace("\u00A0", "").replace(',', '.'));
        }
        return last;
    }
"@.Trim(), @"
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
            || normalized.contains("���");
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
"@.Trim())
$content = $content.Replace('    private record ServiceLeadSplit(String serviceText, String leadToken) { }', "    private record ServiceLeadSplit(String serviceText, String leadToken) { }`r`n`r`n    private record AttachmentMatch(int targetIndex, List<Integer> mergeSupportIndices) { }")
Set-Content -Path $path -Value $content
