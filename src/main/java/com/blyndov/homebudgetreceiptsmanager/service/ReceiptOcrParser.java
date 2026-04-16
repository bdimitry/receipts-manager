package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiptOcrParser {

    private static final Pattern DECIMAL_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2})(?!\\d)"
    );
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{2,4})\\b"
    );
    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[\\d\\s-]{8,}$");
    private static final Pattern QUANTITY_WITH_UNIT_PATTERN = Pattern.compile(
        "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(шт|кг|kg|гр|g|г|л|ml|мл|уп|упак|pcs|pc|pack|бут|btl|pkg)(?=\\s|$)"
    );
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile(
        "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*[xхХ×*]\\s*(\\d+[\\.,]\\d{2})"
    );
    private static final Pattern CURRENCY_UAH_PATTERN = Pattern.compile("(?iu)(uah|грн|₴)");
    private static final Pattern CURRENCY_USD_PATTERN = Pattern.compile("(?iu)(usd|\\$)");
    private static final Pattern CURRENCY_EUR_PATTERN = Pattern.compile("(?iu)(eur|€)");
    private static final Pattern CURRENCY_RUB_PATTERN = Pattern.compile("(?iu)(rub|₽)");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yy"),
        DateTimeFormatter.ofPattern("dd/MM/yy"),
        DateTimeFormatter.ofPattern("dd-MM-yy")
    );
    private static final Set<String> TOTAL_KEYWORDS = Set.of(
        "total",
        "amount due",
        "sum",
        "итого",
        "сумма",
        "сума",
        "всього",
        "разом",
        "до сплати",
        "к оплате",
        "оплата"
    );
    private static final Set<String> GENERIC_HEADER_LINES = Set.of(
        "receipt",
        "cash receipt",
        "document",
        "thank you",
        "дякуємо",
        "спасибо"
    );
    private static final Set<String> SERVICE_KEYWORDS = Set.of(
        "barcode",
        "ean",
        "штрих",
        "штрихкод",
        "cashier",
        "terminal",
        "address",
        "www",
        "http",
        "qr",
        "thank",
        "discount",
        "vat",
        "tax",
        "пдв",
        "phone",
        "тел",
        "касса",
        "каса",
        "кассир",
        "касир",
        "терминал",
        "термінал"
    );
    private static final Set<String> ACCOUNT_KEYWORDS = Set.of(
        "iban",
        "swift",
        "edrpou",
        "єдрпоу",
        "рахунок",
        "рахунку",
        "рахунків",
        "account",
        "invoice",
        "отримувач",
        "одержувач",
        "відправник",
        "платіж",
        "переказ"
    );

    public ParsedReceiptDocument parse(NormalizedOcrDocument document) {
        if (document == null) {
            return new ParsedReceiptDocument(null, null, null, null, List.of());
        }

        List<NormalizedOcrLineResponse> orderedLines = document.normalizedLines().stream()
            .sorted(Comparator.comparing(line -> line.order() == null ? Integer.MAX_VALUE : line.order()))
            .toList();
        List<NormalizedOcrLineResponse> parserReadyLines = document.parserReadyLines().isEmpty()
            ? orderedLines.stream().filter(line -> StringUtils.hasText(line.normalizedText())).toList()
            : document.parserReadyLines();

        return new ParsedReceiptDocument(
            extractMerchant(orderedLines).orElse(null),
            extractPurchaseDate(orderedLines).orElse(null),
            extractTotalAmount(orderedLines).orElse(null),
            extractCurrency(orderedLines).orElse(null),
            extractLineItems(parserReadyLines)
        );
    }

    private Optional<String> extractMerchant(List<NormalizedOcrLineResponse> lines) {
        return lines.stream()
            .limit(8)
            .filter(line -> !line.ignored())
            .map(line -> new MerchantCandidate(line, sanitizeTitle(line.normalizedText())))
            .filter(candidate -> StringUtils.hasText(candidate.title()))
            .filter(candidate -> containsLetters(candidate.title()))
            .filter(candidate -> !containsMoney(candidate.title()))
            .filter(candidate -> !containsDateOrTimeMetadata(candidate.title()))
            .filter(candidate -> !containsTotalKeyword(candidate.title()))
            .filter(candidate -> !isAccountLike(candidate.title()))
            .filter(candidate -> !GENERIC_HEADER_LINES.contains(normalizeForMatching(candidate.title())))
            .sorted(
                Comparator.comparingInt((MerchantCandidate candidate) -> merchantScore(candidate.line(), candidate.title()))
                    .thenComparingInt(candidate -> candidate.line().order() == null ? Integer.MAX_VALUE : candidate.line().order())
                    .thenComparingInt(candidate -> candidate.title().length())
            )
            .map(MerchantCandidate::title)
            .findFirst();
    }

    private Optional<LocalDate> extractPurchaseDate(List<NormalizedOcrLineResponse> lines) {
        for (NormalizedOcrLineResponse line : lines) {
            Optional<LocalDate> parsedDate = extractDateFromText(line.normalizedText());
            if (parsedDate.isPresent()) {
                return parsedDate;
            }
        }

        return Optional.empty();
    }

    private Optional<BigDecimal> extractTotalAmount(List<NormalizedOcrLineResponse> lines) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).normalizedText();
            if (!StringUtils.hasText(line)) {
                continue;
            }

            if (containsTotalKeyword(line)) {
                List<BigDecimal> amounts = extractAllDecimals(line);
                if (!amounts.isEmpty()) {
                    return Optional.of(amounts.getLast());
                }
            }
        }

        for (int index = lines.size() - 1; index >= 0; index--) {
            NormalizedOcrLineResponse line = lines.get(index);
            if (line.ignored()) {
                continue;
            }
            if (!line.tags().contains("price_like")) {
                continue;
            }
            if (looksLikeItemLine(line)) {
                continue;
            }

            List<BigDecimal> amounts = extractAllDecimals(line.normalizedText());
            if (!amounts.isEmpty()) {
                return Optional.of(amounts.getLast());
            }
        }

        return Optional.empty();
    }

    private Optional<CurrencyCode> extractCurrency(List<NormalizedOcrLineResponse> lines) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).normalizedText();
            if (!StringUtils.hasText(line)) {
                continue;
            }

            Optional<CurrencyCode> explicitCurrency = extractCurrencyFromText(line);
            if (explicitCurrency.isPresent()) {
                return explicitCurrency;
            }
        }

        return Optional.empty();
    }

    private List<ParsedReceiptLineItem> extractLineItems(List<NormalizedOcrLineResponse> lines) {
        List<ParsedReceiptLineItem> items = new ArrayList<>();
        String pendingTitle = null;
        List<String> pendingSourceLines = new ArrayList<>();
        int lineIndex = 0;

        for (NormalizedOcrLineResponse line : lines) {
            String text = line.normalizedText();
            if (!StringUtils.hasText(text) || line.ignored()) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                continue;
            }

            if (containsTotalKeyword(text)) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                break;
            }

            if (shouldIgnoreForLineItems(line)) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                continue;
            }

            List<DecimalMatch> matches = extractDecimalMatches(text);
            boolean hasLetters = containsLetters(text);

            if (!matches.isEmpty() && hasLetters) {
                ParsedReceiptLineItem item = parseInlineLineItem(line, matches, pendingTitle, pendingSourceLines, ++lineIndex);
                if (item != null) {
                    items.add(item);
                    pendingTitle = null;
                    pendingSourceLines = new ArrayList<>();
                    continue;
                }
            }

            if (!matches.isEmpty() && !hasLetters && StringUtils.hasText(pendingTitle)) {
                ParsedReceiptLineItem item = parsePendingAmountLine(line, matches, pendingTitle, pendingSourceLines, ++lineIndex);
                if (item != null) {
                    items.add(item);
                }
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                continue;
            }

            if (looksLikePotentialItemTitle(line)) {
                String normalizedTitle = sanitizeTitle(text);
                pendingTitle = StringUtils.hasText(pendingTitle)
                    ? sanitizeTitle(pendingTitle + " " + normalizedTitle)
                    : normalizedTitle;
                pendingSourceLines.add(sourceText(line));
            } else {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
            }
        }

        return items;
    }

    private ParsedReceiptLineItem parseInlineLineItem(
        NormalizedOcrLineResponse line,
        List<DecimalMatch> matches,
        String pendingTitle,
        List<String> pendingSourceLines,
        int lineIndex
    ) {
        String text = line.normalizedText();
        String titlePart = sanitizeTitle(text.substring(0, matches.getFirst().start()));
        String title = StringUtils.hasText(pendingTitle)
            ? sanitizeTitle(pendingTitle + " " + titlePart)
            : titlePart;

        if (!StringUtils.hasText(title)) {
            return null;
        }

        QuantityDetails quantityDetails = detectQuantity(title, text, matches);
        BigDecimal lineTotal = matches.getLast().value();
        BigDecimal unitPrice = quantityDetails.unitPrice() != null
            ? quantityDetails.unitPrice()
            : (matches.size() >= 2 ? matches.get(matches.size() - 2).value() : null);

        if (unitPrice == null && quantityDetails.quantity() != null && quantityDetails.quantity().compareTo(BigDecimal.ZERO) > 0) {
            unitPrice = lineTotal.divide(quantityDetails.quantity(), 2, RoundingMode.HALF_UP);
        }

        List<String> sourceLines = new ArrayList<>(pendingSourceLines);
        sourceLines.add(sourceText(line));

        return new ParsedReceiptLineItem(
            lineIndex,
            title,
            quantityDetails.quantity(),
            quantityDetails.unit(),
            unitPrice,
            lineTotal,
            String.join(" | ", sourceLines),
            List.copyOf(sourceLines)
        );
    }

    private ParsedReceiptLineItem parsePendingAmountLine(
        NormalizedOcrLineResponse line,
        List<DecimalMatch> matches,
        String pendingTitle,
        List<String> pendingSourceLines,
        int lineIndex
    ) {
        String title = sanitizeTitle(pendingTitle);
        if (!StringUtils.hasText(title)) {
            return null;
        }

        QuantityDetails quantityDetails = detectQuantity(title, pendingTitle, matches);
        BigDecimal lineTotal = matches.getLast().value();
        BigDecimal unitPrice = quantityDetails.unitPrice();
        if (unitPrice == null && matches.size() >= 2) {
            unitPrice = matches.get(matches.size() - 2).value();
        }
        if (unitPrice == null && quantityDetails.quantity() != null && quantityDetails.quantity().compareTo(BigDecimal.ZERO) > 0) {
            unitPrice = lineTotal.divide(quantityDetails.quantity(), 2, RoundingMode.HALF_UP);
        }

        List<String> sourceLines = new ArrayList<>(pendingSourceLines);
        sourceLines.add(sourceText(line));

        return new ParsedReceiptLineItem(
            lineIndex,
            title,
            quantityDetails.quantity(),
            quantityDetails.unit(),
            unitPrice,
            lineTotal,
            String.join(" | ", sourceLines),
            List.copyOf(sourceLines)
        );
    }

    private QuantityDetails detectQuantity(String title, String rawLine, List<DecimalMatch> matches) {
        QuantityWithUnit quantityWithUnit = extractQuantityWithUnit(title);
        if (quantityWithUnit != null) {
            return new QuantityDetails(quantityWithUnit.quantity(), quantityWithUnit.unit(), null);
        }

        quantityWithUnit = extractQuantityWithUnit(rawLine);
        if (quantityWithUnit != null) {
            MultiplierDetails multiplierWithUnit = extractMultiplier(rawLine);
            return new QuantityDetails(
                quantityWithUnit.quantity(),
                quantityWithUnit.unit(),
                multiplierWithUnit != null ? multiplierWithUnit.unitPrice() : null
            );
        }

        MultiplierDetails multiplier = extractMultiplier(rawLine);
        if (multiplier != null) {
            return new QuantityDetails(multiplier.quantity(), null, multiplier.unitPrice());
        }

        if (matches.size() >= 2 && containsMultiplierMarker(title)) {
            return new QuantityDetails(null, null, matches.get(matches.size() - 2).value());
        }

        return new QuantityDetails(null, null, null);
    }

    private boolean shouldIgnoreForLineItems(NormalizedOcrLineResponse line) {
        String text = line.normalizedText();
        return !StringUtils.hasText(text)
            || line.ignored()
            || isBarcodeLike(text)
            || containsDateOrTimeMetadata(text)
            || containsTotalKeyword(text)
            || isAccountLike(text)
            || (line.tags().contains("service_like") && !looksLikeItemLine(line))
            || (line.tags().contains("header_like") && isEarlyHeader(line) && !line.tags().contains("price_like"))
            || text.length() < 2;
    }

    private boolean looksLikePotentialItemTitle(NormalizedOcrLineResponse line) {
        String text = line.normalizedText();
        if (!containsLetters(text) || containsMoney(text) || containsDateOrTimeMetadata(text) || containsTotalKeyword(text) || isAccountLike(text)) {
            return false;
        }
        if ((line.tags().contains("header_like") && isEarlyHeader(line))
            || (line.tags().contains("service_like") && !line.tags().contains("content_like"))) {
            return false;
        }

        long letterCount = text.codePoints().filter(Character::isLetter).count();
        long digitCount = text.codePoints().filter(Character::isDigit).count();
        return letterCount >= 3 && digitCount <= Math.max(6, letterCount * 2L);
    }

    private boolean looksLikeItemLine(NormalizedOcrLineResponse line) {
        String text = line.normalizedText();
        return StringUtils.hasText(text)
            && !line.ignored()
            && containsLetters(text)
            && containsMoney(text)
            && !containsTotalKeyword(text)
            && !containsDateOrTimeMetadata(text)
            && !isAccountLike(text)
            && !(line.tags().contains("header_like") && isEarlyHeader(line));
    }

    private boolean isEarlyHeader(NormalizedOcrLineResponse line) {
        return line.order() != null && line.order() <= 1;
    }

    private int merchantScore(NormalizedOcrLineResponse line, String title) {
        int score = 0;
        if (!line.tags().contains("header_like")) {
            score += 10;
        }
        if (line.tags().contains("service_like")) {
            score += 10;
        }
        if (title.chars().anyMatch(Character::isDigit)) {
            score += 10;
        }
        if (title.length() > 40) {
            score += 5;
        }
        return score;
    }

    private Optional<LocalDate> extractDateFromText(String value) {
        Matcher matcher = DATE_PATTERN.matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    LocalDate date = LocalDate.parse(candidate, formatter);
                    if (candidate.matches("\\d{2}[./-]\\d{2}[./-]\\d{2}$")) {
                        date = date.withYear(adjustTwoDigitYear(date.getYear()));
                    }
                    return Optional.of(date);
                } catch (DateTimeParseException ignored) {
                    // Continue with the next supported format.
                }
            }
        }

        return Optional.empty();
    }

    private Optional<CurrencyCode> extractCurrencyFromText(String value) {
        if (CURRENCY_UAH_PATTERN.matcher(value).find()) {
            return Optional.of(CurrencyCode.UAH);
        }
        if (CURRENCY_USD_PATTERN.matcher(value).find()) {
            return Optional.of(CurrencyCode.USD);
        }
        if (CURRENCY_EUR_PATTERN.matcher(value).find()) {
            return Optional.of(CurrencyCode.EUR);
        }
        if (CURRENCY_RUB_PATTERN.matcher(value).find()) {
            return Optional.of(CurrencyCode.RUB);
        }

        return Optional.empty();
    }

    private boolean containsMoney(String line) {
        return DECIMAL_PATTERN.matcher(line).find();
    }

    private boolean containsLetters(String line) {
        return LETTER_PATTERN.matcher(line).find();
    }

    private boolean containsDateOrTimeMetadata(String line) {
        return DATE_PATTERN.matcher(line).find() || line.matches(".*\\b\\d{2}:\\d{2}\\b.*");
    }

    private boolean containsTotalKeyword(String line) {
        String normalized = normalizeForMatching(line);
        return TOTAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isAccountLike(String line) {
        String normalized = normalizeForMatching(line);
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        boolean ibanLike = normalized.contains("ua") && digitCount >= 10;
        return ibanLike || ACCOUNT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isBarcodeLike(String line) {
        return BARCODE_PATTERN.matcher(line.replace('\u00A0', ' ')).matches();
    }

    private boolean containsMultiplierMarker(String value) {
        String normalized = normalizeForMatching(value);
        return normalized.endsWith(" x")
            || normalized.endsWith(" х")
            || normalized.endsWith(" ×")
            || normalized.endsWith(" *");
    }

    private String normalizeWhitespace(String value) {
        return value.replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String normalizeForMatching(String value) {
        return normalizeWhitespace(value).toLowerCase(Locale.ROOT);
    }

    private String sanitizeTitle(String value) {
        String normalized = normalizeWhitespace(value);
        return normalized
            .replaceAll("(?iu)\\s+\\d+(?:[\\.,]\\d+)?\\s*[xхХ×*]$", "")
            .replaceAll("(?iu)\\s*[xхХ×*]$", "")
            .replaceAll("[\\s,;:/\\\\|.-]+$", "");
    }

    private String sourceText(NormalizedOcrLineResponse line) {
        return StringUtils.hasText(line.originalText()) ? line.originalText() : line.normalizedText();
    }

    private int adjustTwoDigitYear(int parsedYear) {
        return parsedYear < 100 ? 2000 + parsedYear : parsedYear;
    }

    private List<BigDecimal> extractAllDecimals(String text) {
        return extractDecimalMatches(text).stream().map(DecimalMatch::value).toList();
    }

    private List<DecimalMatch> extractDecimalMatches(String text) {
        Matcher matcher = DECIMAL_PATTERN.matcher(text);
        List<DecimalMatch> decimals = new ArrayList<>();
        while (matcher.find()) {
            decimals.add(new DecimalMatch(parseMoney(matcher.group(1)), matcher.start(), matcher.end()));
        }
        return decimals;
    }

    private BigDecimal parseMoney(String value) {
        return new BigDecimal(value.replace('\u00A0', ' ').replace(" ", "").replace(",", ".").trim());
    }

    private BigDecimal parseNumber(String value) {
        return new BigDecimal(value.replace(",", "."));
    }

    private QuantityWithUnit extractQuantityWithUnit(String value) {
        Matcher matcher = QUANTITY_WITH_UNIT_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }

        return new QuantityWithUnit(parseNumber(matcher.group(1)), matcher.group(2).toLowerCase(Locale.ROOT));
    }

    private MultiplierDetails extractMultiplier(String value) {
        Matcher matcher = MULTIPLIER_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }

        return new MultiplierDetails(parseNumber(matcher.group(1)), parseMoney(matcher.group(2)));
    }

    private record DecimalMatch(BigDecimal value, int start, int end) {
    }

    private record QuantityDetails(BigDecimal quantity, String unit, BigDecimal unitPrice) {
    }

    private record QuantityWithUnit(BigDecimal quantity, String unit) {
    }

    private record MultiplierDetails(BigDecimal quantity, BigDecimal unitPrice) {
    }

    private record MerchantCandidate(NormalizedOcrLineResponse line, String title) {
    }
}
