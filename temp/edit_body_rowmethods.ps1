$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$content = $content.Replace(@"
        private boolean isAmountOnly() {
            String text = text();
            return StringUtils.hasText(text)
                && AMOUNT_ONLY_PATTERN.matcher(text).matches()
                && !keywordLexicon.containsSummaryKeyword(text);
        }
"@.Trim(), @"
        private boolean isAmountOnly() {
            String text = normalizeAmountTypography(text());
            return StringUtils.hasText(text)
                && AMOUNT_ONLY_PATTERN.matcher(text).matches()
                && !keywordLexicon.containsSummaryKeyword(text);
        }
"@.Trim())
$content = $content.Replace(@"
        private boolean isSummaryLike() {
            String text = text();
            return keywordLexicon.containsSummaryKeyword(text)
                || keywordLexicon.containsTaxKeyword(text)
                || PERCENT_PATTERN.matcher(text).find();
        }
"@.Trim(), @"
        private boolean isSummaryLike() {
            String text = text();
            String normalized = keywordLexicon.normalizeForMatching(text);
            return keywordLexicon.containsSummaryKeyword(text)
                || keywordLexicon.containsTaxKeyword(text)
                || looksLikeTaxSummaryText(text, normalized);
        }
"@.Trim())
$content = $content.Replace(@"
        private boolean isCardTailLike() {
"@.Trim(), @"
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
            if (!StringUtils.hasText(text) || isLikelyItemLike() || isSummaryLike() || isServiceLike()) {
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
"@.Trim())
Set-Content -Path $path -Value $content
