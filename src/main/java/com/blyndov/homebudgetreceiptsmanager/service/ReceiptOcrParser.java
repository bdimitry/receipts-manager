package com.blyndov.homebudgetreceiptsmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Pattern LETTER_PATTERN = Pattern.compile(".*\\p{L}.*");
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[\\d\\s-]{8,}$");
    private static final Pattern QUANTITY_WITH_UNIT_PATTERN = Pattern.compile(
        "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(шт|кг|kg|гр|g|г|л|ml|мл|уп|упак|pcs|pc|pack|бут|btl|pkg)(?=\\s|$)"
    );
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile(
        "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*[xх×*]\\s*(\\d+[\\.,]\\d{2})"
    );
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
        "subtotal",
        "итого",
        "сумма",
        "сума",
        "всього",
        "до сплати",
        "к оплате"
    );
    private static final Set<String> SERVICE_KEYWORDS = Set.of(
        "barcode",
        "bar code",
        "штрих",
        "штрихкод",
        "штрих код",
        "касса",
        "каса",
        "кассир",
        "касир",
        "cashier",
        "terminal",
        "терминал",
        "термінал",
        "address",
        "адрес",
        "www",
        "http",
        "qr",
        "receipt",
        "чек",
        "фискал",
        "фіскал",
        "thank",
        "дякуємо",
        "спасибо",
        "card",
        "payment",
        "discount",
        "зниж",
        "скид",
        "vat",
        "tax",
        "пдв",
        "phone",
        "тел"
    );
    private static final Set<String> ADDRESS_KEYWORDS = Set.of(
        "вул",
        "ул",
        "улица",
        "street",
        "st.",
        "avenue",
        "ave",
        "просп",
        "пр-т",
        "буд",
        "дом",
        "місто",
        "город"
    );

    public ParsedReceiptData parse(String rawText) {
        List<String> lines = rawText.lines()
            .map(this::normalizeWhitespace)
            .filter(StringUtils::hasText)
            .toList();

        return new ParsedReceiptData(
            extractStoreName(lines).orElse(null),
            extractTotalAmount(lines, rawText).orElse(null),
            extractPurchaseDate(rawText).orElse(null),
            extractLineItems(lines)
        );
    }

    private Optional<String> extractStoreName(List<String> lines) {
        return lines.stream()
            .limit(8)
            .filter(this::containsLetters)
            .filter(line -> !isServiceLine(line))
            .filter(line -> !containsMoney(line))
            .filter(line -> !isBarcodeLike(line))
            .filter(line -> !containsDateOrTimeMetadata(line))
            .map(this::sanitizeTitle)
            .filter(StringUtils::hasText)
            .findFirst();
    }

    private Optional<BigDecimal> extractTotalAmount(List<String> lines, String rawText) {
        for (String line : lines) {
            if (!containsTotalKeyword(line) || line.toLowerCase(Locale.ROOT).contains("subtotal")) {
                continue;
            }

            List<BigDecimal> amounts = extractAllDecimals(line);
            if (!amounts.isEmpty()) {
                return Optional.of(amounts.getLast());
            }
        }

        List<BigDecimal> allDecimals = extractAllDecimals(rawText);
        return allDecimals.stream().max(Comparator.naturalOrder());
    }

    private Optional<LocalDate> extractPurchaseDate(String rawText) {
        Matcher matcher = DATE_PATTERN.matcher(rawText);
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
                    // Try the next supported format.
                }
            }
        }

        return Optional.empty();
    }

    private List<ParsedReceiptLineItem> extractLineItems(List<String> lines) {
        List<ParsedReceiptLineItem> items = new ArrayList<>();
        String pendingTitle = null;
        String pendingRawFragment = null;
        int lineIndex = 0;
        boolean catalogStarted = false;

        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }

            if (containsTotalKeyword(line)) {
                pendingTitle = null;
                pendingRawFragment = null;
                break;
            }

            if (shouldIgnoreForLineItems(line)) {
                pendingTitle = null;
                pendingRawFragment = null;
                continue;
            }

            List<DecimalMatch> matches = extractDecimalMatches(line);
            boolean hasLetters = containsLetters(line);

            if (!catalogStarted) {
                if (!matches.isEmpty() && hasLetters && !containsDateOrTimeMetadata(line)) {
                    catalogStarted = true;
                } else if (looksLikePotentialItemTitle(line)) {
                    catalogStarted = true;
                    pendingTitle = sanitizeTitle(line);
                    pendingRawFragment = line;
                    continue;
                } else {
                    continue;
                }
            }

            if (!matches.isEmpty() && hasLetters) {
                ParsedReceiptLineItem item = parseInlineLineItem(line, matches, pendingTitle, pendingRawFragment, ++lineIndex);
                if (item != null) {
                    items.add(item);
                    pendingTitle = null;
                    pendingRawFragment = null;
                    continue;
                }
            }

            if (!matches.isEmpty() && !hasLetters && StringUtils.hasText(pendingTitle)) {
                ParsedReceiptLineItem item = parsePendingAmountLine(line, matches, pendingTitle, pendingRawFragment, ++lineIndex);
                if (item != null) {
                    items.add(item);
                }
                pendingTitle = null;
                pendingRawFragment = null;
                continue;
            }

            if (looksLikePotentialItemTitle(line)) {
                String normalizedTitle = sanitizeTitle(line);
                pendingTitle = StringUtils.hasText(pendingTitle)
                    ? sanitizeTitle(pendingTitle + " " + normalizedTitle)
                    : normalizedTitle;
                pendingRawFragment = StringUtils.hasText(pendingRawFragment)
                    ? pendingRawFragment + " | " + line
                    : line;
            } else {
                pendingTitle = null;
                pendingRawFragment = null;
            }
        }

        return items;
    }

    private ParsedReceiptLineItem parseInlineLineItem(
        String line,
        List<DecimalMatch> matches,
        String pendingTitle,
        String pendingRawFragment,
        int lineIndex
    ) {
        String titlePart = sanitizeTitle(line.substring(0, matches.getFirst().start()));
        String title = StringUtils.hasText(pendingTitle)
            ? sanitizeTitle(pendingTitle + " " + titlePart)
            : titlePart;

        if (!StringUtils.hasText(title)) {
            return null;
        }

        QuantityDetails quantityDetails = detectQuantity(title, line, matches);
        BigDecimal lineTotal = matches.getLast().value();
        BigDecimal unitPrice = quantityDetails.unitPrice() != null
            ? quantityDetails.unitPrice()
            : (matches.size() >= 2 ? matches.get(matches.size() - 2).value() : null);

        if (unitPrice == null && quantityDetails.quantity() != null && quantityDetails.quantity().compareTo(BigDecimal.ZERO) > 0) {
            unitPrice = lineTotal.divide(quantityDetails.quantity(), 2, RoundingMode.HALF_UP);
        }

        String rawFragment = StringUtils.hasText(pendingRawFragment) ? pendingRawFragment + " | " + line : line;

        return new ParsedReceiptLineItem(
            lineIndex,
            title,
            quantityDetails.quantity(),
            quantityDetails.unit(),
            unitPrice,
            lineTotal,
            rawFragment
        );
    }

    private ParsedReceiptLineItem parsePendingAmountLine(
        String line,
        List<DecimalMatch> matches,
        String pendingTitle,
        String pendingRawFragment,
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

        return new ParsedReceiptLineItem(
            lineIndex,
            title,
            quantityDetails.quantity(),
            quantityDetails.unit(),
            unitPrice,
            lineTotal,
            StringUtils.hasText(pendingRawFragment) ? pendingRawFragment + " | " + line : line
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

    private boolean shouldIgnoreForLineItems(String line) {
        return !StringUtils.hasText(line)
            || isBarcodeLike(line)
            || isServiceLine(line)
            || containsDateOrTimeMetadata(line)
            || line.length() < 2;
    }

    private boolean looksLikePotentialItemTitle(String line) {
        if (!containsLetters(line) || containsMoney(line) || containsDateOrTimeMetadata(line) || isServiceLine(line) || isBarcodeLike(line)) {
            return false;
        }

        long letterCount = line.codePoints().filter(Character::isLetter).count();
        long digitCount = line.codePoints().filter(Character::isDigit).count();
        return letterCount >= 3 && digitCount <= Math.max(6, letterCount * 2);
    }

    private boolean containsMoney(String line) {
        return DECIMAL_PATTERN.matcher(line).find();
    }

    private boolean containsLetters(String line) {
        return LETTER_PATTERN.matcher(line).matches();
    }

    private boolean containsDateOrTimeMetadata(String line) {
        return DATE_PATTERN.matcher(line).find() || line.matches(".*\\b\\d{2}:\\d{2}\\b.*");
    }

    private boolean isServiceLine(String line) {
        String normalized = normalizeForMatching(line);
        return SERVICE_KEYWORDS.stream().anyMatch(normalized::contains) || isAddressLike(normalized);
    }

    private boolean isAddressLike(String normalizedLine) {
        return ADDRESS_KEYWORDS.stream().anyMatch(normalizedLine::contains) && normalizedLine.chars().anyMatch(Character::isDigit);
    }

    private boolean containsTotalKeyword(String line) {
        String normalized = normalizeForMatching(line);
        return TOTAL_KEYWORDS.stream().anyMatch(normalized::contains);
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
            .replaceAll("(?iu)\\s+\\d+(?:[\\.,]\\d+)?\\s*[xх×*]$", "")
            .replaceAll("(?iu)\\s*[xх×*]$", "")
            .replaceAll("[\\s,;:/\\\\|.-]+$", "");
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

    public record ParsedReceiptData(
        String parsedStoreName,
        BigDecimal parsedTotalAmount,
        LocalDate parsedPurchaseDate,
        List<ParsedReceiptLineItem> lineItems
    ) {
    }

    public record ParsedReceiptLineItem(
        Integer lineIndex,
        String title,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String rawFragment
    ) {
    }

    private record DecimalMatch(BigDecimal value, int start, int end) {
    }

    private record QuantityDetails(BigDecimal quantity, String unit, BigDecimal unitPrice) {
    }

    private record QuantityWithUnit(BigDecimal quantity, String unit) {
    }

    private record MultiplierDetails(BigDecimal quantity, BigDecimal unitPrice) {
    }
}
