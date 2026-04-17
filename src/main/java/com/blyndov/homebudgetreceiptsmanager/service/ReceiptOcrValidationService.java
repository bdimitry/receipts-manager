package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiptOcrValidationService {

    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern ALPHANUMERIC_MIXED_TOKEN_PATTERN = Pattern.compile("(?iu)(?=.*\\p{L})(?=.*\\d)[\\p{L}\\d$#/\\\\-]+");
    private static final Pattern DATE_LINE_PATTERN = Pattern.compile("\\b\\d{2}[./-]\\d{2}[./-]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("(?<!\\d)\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2}(?!\\d)");
    private static final Pattern MASKED_CARD_PATTERN = Pattern.compile("(?iu)(x{4,}|\\*{4,}|master\\s*card|visa)");
    private static final Pattern BANKISH_MARKER_PATTERN = Pattern.compile("(?iu)(ukrsib|ukr\\$ib|ontine|online|iban|swift|edrpou|mfo|account|invoice|rahun|рахун|отримувач|відправник|bank|bnp|payment|trans|onepayia)");
    private static final Set<String> PAYMENT_KEYWORDS = Set.of(
        "mastercard", "visa", "privat", "bank", "card", "payment", "pay", "online",
        "trans", "auth", "system", "terminal", "iban", "swift", "edrpou", "account",
        "invoice", "рахунок", "платіж", "переказ", "отримувач", "відправник"
    );
    private static final Set<String> TOTAL_KEYWORDS = Set.of(
        "total", "amount due", "sum", "suma", "сум", "итого", "всього", "разом", "до сплати", "к оплате"
    );
    private static final Set<String> GENERIC_HEADERS = Set.of(
        "receipt", "cash receipt", "document", "thank you", "дякуємо", "спасибо"
    );
    private static final Set<String> TRUSTED_MERCHANTS = Set.of("novus", "ukrsibbank", "fresh market", "coffee house");

    public ParsedReceiptValidationResult validate(NormalizedOcrDocument document, ParsedReceiptDocument parsedDocument) {
        if (document == null || parsedDocument == null) {
            return new ParsedReceiptValidationResult(List.of(), false);
        }

        LinkedHashSet<ReceiptParseWarningCode> warnings = new LinkedHashSet<>();

        validateMerchant(document, parsedDocument).ifPresent(warnings::add);
        validateDate(parsedDocument).ifPresent(warnings::add);
        validateTotal(document, parsedDocument).forEach(warnings::add);
        validateLineItems(document, parsedDocument).forEach(warnings::add);

        boolean weakParseQuality = warnings.contains(ReceiptParseWarningCode.SUSPICIOUS_MERCHANT)
            || warnings.contains(ReceiptParseWarningCode.SUSPICIOUS_TOTAL)
            || warnings.contains(ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS)
            || warnings.size() >= 2
            || (!StringUtils.hasText(parsedDocument.merchantName())
                && parsedDocument.totalAmount() == null
                && parsedDocument.lineItems().isEmpty());

        return new ParsedReceiptValidationResult(List.copyOf(warnings), weakParseQuality);
    }

    private Optional<ReceiptParseWarningCode> validateMerchant(
        NormalizedOcrDocument document,
        ParsedReceiptDocument parsedDocument
    ) {
        String merchantName = parsedDocument.merchantName();
        if (!StringUtils.hasText(merchantName)) {
            return Optional.of(ReceiptParseWarningCode.SUSPICIOUS_MERCHANT);
        }

        String normalizedMerchant = normalizeForMatching(merchantName);
        if (TRUSTED_MERCHANTS.contains(normalizedMerchant)) {
            return Optional.empty();
        }

        long letterCount = countLetters(merchantName);
        long uniqueLetters = normalizedMerchant.codePoints().filter(Character::isLetter).distinct().count();
        long wordCount = merchantName.trim().split("\\s+").length;
        boolean shortLeadWord = merchantName.trim().contains(" ")
            && merchantName.trim().split("\\s+")[0].length() <= 2;
        boolean hasDigits = merchantName.chars().anyMatch(Character::isDigit);
        boolean genericHeader = GENERIC_HEADERS.contains(normalizedMerchant);
        boolean serviceLike = PAYMENT_KEYWORDS.stream().anyMatch(normalizedMerchant::contains)
            || TOTAL_KEYWORDS.stream().anyMatch(normalizedMerchant::contains);

        Optional<NormalizedOcrLineResponse> sourceLine = document.normalizedLines().stream()
            .limit(10)
            .filter(line -> StringUtils.hasText(line.normalizedText()))
            .filter(line -> normalizeForMatching(line.normalizedText()).contains(normalizedMerchant))
            .findFirst();

        boolean suspiciousSourceLine = sourceLine.isEmpty()
            || sourceLine.get().tags().contains("price_like")
            || sourceLine.get().tags().contains("service_like")
            || sourceLine.get().ignored();

        if (letterCount < 4
            || uniqueLetters <= 2
            || hasDigits
            || shortLeadWord
            || genericHeader
            || serviceLike
            || suspiciousSourceLine
            || (wordCount == 1 && merchantName.length() <= 4)) {
            return Optional.of(ReceiptParseWarningCode.SUSPICIOUS_MERCHANT);
        }

        return Optional.empty();
    }

    private Optional<ReceiptParseWarningCode> validateDate(ParsedReceiptDocument parsedDocument) {
        LocalDate purchaseDate = parsedDocument.purchaseDate();
        if (purchaseDate == null) {
            return Optional.empty();
        }

        LocalDate now = LocalDate.now();
        if (purchaseDate.isAfter(now.plusDays(1))
            || purchaseDate.getYear() < 2000
            || purchaseDate.isBefore(now.minusYears(5))) {
            return Optional.of(ReceiptParseWarningCode.SUSPICIOUS_DATE);
        }

        return Optional.empty();
    }

    private List<ReceiptParseWarningCode> validateTotal(
        NormalizedOcrDocument document,
        ParsedReceiptDocument parsedDocument
    ) {
        LinkedHashSet<ReceiptParseWarningCode> warnings = new LinkedHashSet<>();
        BigDecimal totalAmount = parsedDocument.totalAmount();
        LocalDate purchaseDate = parsedDocument.purchaseDate();
        BigDecimal itemSum = parsedDocument.lineItems().stream()
            .map(ParsedReceiptLineItem::lineTotal)
            .filter(value -> value != null && value.compareTo(BigDecimal.ZERO) > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean hasSummaryContext = document.parserReadyLines().stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .filter(StringUtils::hasText)
            .map(this::normalizeForMatching)
            .anyMatch(text -> TOTAL_KEYWORDS.stream().anyMatch(text::contains));

        if (totalAmount == null) {
            if (hasSummaryContext || itemSum.compareTo(BigDecimal.ZERO) > 0) {
                warnings.add(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
            }
            return List.copyOf(warnings);
        }

        if (totalAmount.compareTo(BigDecimal.ONE) < 0 || totalAmount.compareTo(new BigDecimal("100000.00")) > 0) {
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
        }

        if (purchaseDate != null) {
            BigDecimal dayMonth = new BigDecimal("%d.%02d".formatted(purchaseDate.getDayOfMonth(), purchaseDate.getMonthValue()));
            BigDecimal monthDay = new BigDecimal("%d.%02d".formatted(purchaseDate.getMonthValue(), purchaseDate.getDayOfMonth()));
            if (sameMoney(totalAmount, dayMonth) || sameMoney(totalAmount, monthDay)) {
                warnings.add(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
            }
        }

        if (itemSum.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal difference = itemSum.subtract(totalAmount).abs();
            BigDecimal tolerance = new BigDecimal("1.00").max(totalAmount.multiply(new BigDecimal("0.15")));
            if (difference.compareTo(tolerance) > 0) {
                warnings.add(ReceiptParseWarningCode.ITEM_TOTAL_MISMATCH);
                warnings.add(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
            }
        }

        boolean totalLooksLikeDateFragment = document.parserReadyLines().stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .filter(StringUtils::hasText)
            .filter(text -> DATE_LINE_PATTERN.matcher(text).find())
            .map(this::extractDayMonthAmounts)
            .flatMap(List::stream)
            .anyMatch(candidate -> sameMoney(candidate, totalAmount));

        if (totalLooksLikeDateFragment) {
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
        }

        return List.copyOf(warnings);
    }

    private List<ReceiptParseWarningCode> validateLineItems(
        NormalizedOcrDocument document,
        ParsedReceiptDocument parsedDocument
    ) {
        LinkedHashSet<ReceiptParseWarningCode> warnings = new LinkedHashSet<>();
        List<ParsedReceiptLineItem> lineItems = parsedDocument.lineItems();
        if (lineItems.isEmpty()) {
            return List.of();
        }

        if (parsedDocument.totalAmount() == null) {
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        }

        boolean paymentContentInItems = lineItems.stream().anyMatch(this::looksLikePaymentOrServiceItem);
        if (paymentContentInItems || (looksLikeBankDocument(document) && !lineItems.isEmpty())) {
            warnings.add(ReceiptParseWarningCode.PAYMENT_CONTENT_IN_ITEMS);
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        }

        boolean noisyTitles = lineItems.stream().anyMatch(this::looksLikeNoisyItemTitle);
        if (noisyTitles) {
            warnings.add(ReceiptParseWarningCode.NOISY_ITEM_TITLES);
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        }

        boolean inconsistentMath = lineItems.stream().anyMatch(this::hasInconsistentItemMath);
        if (inconsistentMath) {
            warnings.add(ReceiptParseWarningCode.INCONSISTENT_ITEM_MATH);
            warnings.add(ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        }

        return List.copyOf(warnings);
    }

    private boolean looksLikePaymentOrServiceItem(ParsedReceiptLineItem item) {
        return StreamSupport.texts(item).stream()
            .map(this::normalizeForMatching)
            .anyMatch(normalized -> PAYMENT_KEYWORDS.stream().anyMatch(normalized::contains)
                || BANKISH_MARKER_PATTERN.matcher(normalized).find()
                || normalized.contains("barcode")
                || normalized.contains("mask")
                || MASKED_CARD_PATTERN.matcher(normalized).find());
    }

    private boolean looksLikeNoisyItemTitle(ParsedReceiptLineItem item) {
        String title = item.title();
        if (!StringUtils.hasText(title)) {
            return true;
        }

        String normalized = normalizeForMatching(title);
        long letters = countLetters(title);
        long digits = title.codePoints().filter(Character::isDigit).count();
        long punctuation = title.codePoints().filter(ch -> !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch)).count();
        List<String> tokens = Stream.of(normalized.split("\\s+"))
            .filter(StringUtils::hasText)
            .toList();
        long lowQualityTokens = tokens.stream()
            .filter(token -> token.length() == 1
                || ALPHANUMERIC_MIXED_TOKEN_PATTERN.matcher(token).matches()
                || token.contains("/")
                || token.contains("\\")
                || token.contains("$"))
            .count();
        boolean lowSignal = letters < 4 || normalized.length() < 4;
        boolean digitHeavy = digits > Math.max(4, letters);
        boolean punctuationHeavy = punctuation > Math.max(2, letters / 2);
        boolean noVowelHint = letters >= 4 && !normalized.matches(".*[aeiouyаеєиіїоуюя].*");
        boolean shortFirstToken = !tokens.isEmpty() && tokens.get(0).length() == 1;
        boolean tokenQualityLooksBroken = !tokens.isEmpty() && lowQualityTokens * 2 >= tokens.size();
        boolean merchantishGarbage = tokens.size() <= 2 && shortFirstToken;

        return lowSignal || digitHeavy || punctuationHeavy || noVowelHint || tokenQualityLooksBroken || merchantishGarbage;
    }

    private boolean hasInconsistentItemMath(ParsedReceiptLineItem item) {
        if (item.quantity() == null || item.unitPrice() == null || item.lineTotal() == null) {
            return false;
        }

        if (item.quantity().compareTo(BigDecimal.ZERO) <= 0 || item.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }

        BigDecimal expected = item.quantity().multiply(item.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = expected.subtract(item.lineTotal()).abs();
        return difference.compareTo(new BigDecimal("0.05")) > 0;
    }

    private boolean looksLikeBankDocument(NormalizedOcrDocument document) {
        long bankishLines = document.normalizedLines().stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .filter(StringUtils::hasText)
            .map(this::normalizeForMatching)
            .filter(text -> PAYMENT_KEYWORDS.stream().anyMatch(text::contains)
                || BANKISH_MARKER_PATTERN.matcher(text).find())
            .count();

        return bankishLines >= 3;
    }

    private List<BigDecimal> extractDayMonthAmounts(String text) {
        String normalized = normalizeWhitespace(text);
        java.util.regex.Matcher matcher = DATE_LINE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return List.of();
        }

        String value = matcher.group();
        String[] parts = value.contains("-") ? value.split("-") : value.split("[./]");
        if (parts.length < 3) {
            return List.of();
        }

        int first = parsePart(parts[0]);
        int second = parsePart(parts[1]);
        if (first <= 0 || second <= 0 || first > 31 || second > 12) {
            return List.of();
        }

        return List.of(
            new BigDecimal("%d.%02d".formatted(first, second)),
            new BigDecimal("%d.%02d".formatted(second, first))
        );
    }

    private int parsePart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private boolean sameMoney(BigDecimal left, BigDecimal right) {
        return left.setScale(2, RoundingMode.HALF_UP).compareTo(right.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    private String normalizeWhitespace(String value) {
        return value.replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String normalizeForMatching(String value) {
        return normalizeWhitespace(value).toLowerCase(Locale.ROOT);
    }

    private long countLetters(String value) {
        return value.codePoints().filter(Character::isLetter).count();
    }

    private static final class StreamSupport {

        private static List<String> texts(ParsedReceiptLineItem item) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            if (StringUtils.hasText(item.title())) {
                values.add(item.title());
            }
            if (StringUtils.hasText(item.rawFragment())) {
                values.add(item.rawFragment());
            }
            if (item.sourceLines() != null) {
                item.sourceLines().stream().filter(StringUtils::hasText).forEach(values::add);
            }
            return List.copyOf(values);
        }
    }
}
