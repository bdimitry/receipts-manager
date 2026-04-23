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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiptOcrParser {

    private static final Pattern DECIMAL_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2})(?!\\d)");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./-]\\d{2}[./-]\\d{2,4})\\b");
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile("\\b\\d{2}[./-]\\d{2}[./-]\\d{2,4}\\s*[-–]\\s*\\d{2}[./-]\\d{2}[./-]\\d{2,4}\\b");
    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern BARCODE_PATTERN = Pattern.compile("^[\\d\\s-]{8,}$");
    private static final Pattern PURE_AMOUNT_LINE_PATTERN = Pattern.compile("(?iu)^\\d{1,5}(?:[ \\u00A0]\\d{3})*[\\.,]\\d{2}(?:\\s*(?:a|uah|usd|eur|rub|грн|₴|rph|rpn))?$");
    private static final Pattern LONG_DIGIT_PATTERN = Pattern.compile("\\d{8,}");
    private static final Pattern QUANTITY_PRICE_ONLY_PATTERN = Pattern.compile("(?iu)^\\d+(?:[\\.,]\\d+)?\\s*[\\p{L}]?\\s*[xх×*]\\s*\\d+[\\.,]\\d{2}\\b.*$");
    private static final Pattern QUANTITY_WITH_UNIT_PATTERN = Pattern.compile("(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(шт|кг|kg|гр|g|г|л|ml|мл|уп|упак|pcs|pc|pack|бут|btl|pkg)(?=\\s|$)");
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile("(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*[xх×*]\\s*(\\d+[\\.,]\\d{2})");
    private static final Pattern CURRENCY_UAH_PATTERN = Pattern.compile("(?iu)(uah|грн|₴|rph|rpn)");
    private static final Pattern CURRENCY_USD_PATTERN = Pattern.compile("(?iu)(usd|us\\$)");
    private static final Pattern CURRENCY_EUR_PATTERN = Pattern.compile("(?iu)(eur|€)");
    private static final Pattern CURRENCY_RUB_PATTERN = Pattern.compile("(?iu)(rub|₽)");
    private static final Pattern MASKED_CARD_PATTERN = Pattern.compile("(?iu)(x{4,}|\\*{4,}|master\\s*card|visa)");
    private static final Pattern ADDRESS_CONTACT_PATTERN = Pattern.compile(
        "(?iu)(?:^|\\b)(address|adress|addr|street|st\\.?|phone|tel|tc\\d*|contact|вул\\.?|район|адрес|тел\\.?|контакт)(?:\\b|\\d)"
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
    private static final Set<String> TOTAL_KEYWORDS = Set.of("total", "amount due", "sum", "suma", "сума", "сумма", "cyma", "итого", "всього", "разом", "до сплати", "к оплате", "оплата");
    private static final Set<String> GENERIC_HEADER_LINES = Set.of("receipt", "cash receipt", "document", "документ", "thank you", "дякуємо", "спасибо");
    private static final Set<String> PAYMENT_KEYWORDS = Set.of("mastercard", "visa", "privat", "bank", "kart", "card", "oplat", "payment", "pay", "zakaz", "online", "trans", "auth", "aut", "bezgot", "gotiv", "gotibo", "gotib", "otibk", "system", "sistem", "sistema", "master", "kaptk", "kartka");
    private static final Set<String> PROMO_KEYWORDS = Set.of("discount", "promo", "special", "spec", "cneu", "spets", "cyhkom", "cyhxom", "зниж", "спец", "cina", "ціна", "цiна", "price");
    private static final Set<String> TAX_KEYWORDS = Set.of("vat", "pdv", "tax", "пдв");
    private static final Set<String> ACCOUNT_KEYWORDS = Set.of("iban", "swift", "edrpou", "єдрпоу", "рахунок", "рахунку", "рахунків", "account", "invoice", "отримувач", "одержувач", "відправник", "платіж", "переказ");
    private static final Map<Pattern, String> MERCHANT_ALIASES = new LinkedHashMap<>();

    static {
        MERCHANT_ALIASES.put(Pattern.compile("(?iu)\\bn[o0]vus\\b|\\bnoyus\\b|\\bnovus\\b"), "NOVUS");
        MERCHANT_ALIASES.put(Pattern.compile("(?iu)ukrsib\\s*bank|укрсиб"), "UkrsibBank");
    }

    private final ReceiptOcrKeywordLexicon keywordLexicon;

    public ReceiptOcrParser(ReceiptOcrKeywordLexicon keywordLexicon) {
        this.keywordLexicon = keywordLexicon;
    }

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
        boolean bankLikeDocument = looksLikeBankLikeDocument(orderedLines);
        List<ParsedReceiptLineItem> lineItems = bankLikeDocument && !containsRetailItemSignal(parserReadyLines)
            ? List.of()
            : extractLineItems(parserReadyLines);

        return new ParsedReceiptDocument(
            extractMerchant(orderedLines).orElse(null),
            extractPurchaseDate(orderedLines).orElse(null),
            extractTotalAmount(orderedLines).orElse(null),
            extractCurrency(orderedLines).orElse(null),
            lineItems
        );
    }

    private Optional<String> extractMerchant(List<NormalizedOcrLineResponse> lines) {
        Optional<MerchantCandidate> topCandidate = lines.stream()
            .limit(12)
            .filter(line -> !line.ignored())
            .map(line -> new MerchantCandidate(line, sanitizeTitle(line.normalizedText())))
            .filter(candidate -> StringUtils.hasText(candidate.title()))
            .filter(candidate -> containsLetters(candidate.title()))
            .filter(candidate -> !containsMoney(candidate.title()))
            .filter(candidate -> !containsDateOrTimeMetadata(candidate.title()))
            .filter(candidate -> !containsTotalKeyword(candidate.title()))
            .filter(candidate -> !isAccountLike(candidate.title()))
            .filter(candidate -> !looksLikeAddressOrContactLine(candidate.title()))
            .filter(candidate -> !looksLikeServiceOrPaymentLine(candidate.title()))
            .filter(candidate -> !looksLikeReceiptMetaLine(candidate.title()))
            .filter(candidate -> !looksLikeProductCodeLine(candidate.title()))
            .filter(candidate -> !keywordLexicon.isGenericHeader(candidate.title()))
            .filter(candidate -> countLetters(candidate.title()) >= 4 || extractMerchantAlias(candidate.title()).isPresent())
            .sorted(
                Comparator.comparingInt((MerchantCandidate candidate) -> merchantScore(candidate.line(), candidate.title()))
                    .thenComparingInt(candidate -> candidate.line().order() == null ? Integer.MAX_VALUE : candidate.line().order())
                    .thenComparingInt(candidate -> candidate.title().length())
            )
            .findFirst();

        Optional<String> aliasCandidate = lines.stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .map(keywordLexicon::extractMerchantAlias)
            .flatMap(Optional::stream)
            .findFirst();

        if (aliasCandidate.isPresent()) {
            return aliasCandidate;
        }

        if (topCandidate.isPresent()
            && merchantScore(topCandidate.get().line(), topCandidate.get().title()) <= 18
            && (topCandidate.get().line().tags().contains("header_like")
                || looksLikeStrongMerchantHeader(topCandidate.get().title()))) {
            return Optional.of(topCandidate.get().title());
        }

        return Optional.empty();
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
            NormalizedOcrLineResponse line = lines.get(index);
            if (isStrongTotalLabelLine(line, index, lines)) {
                Optional<BigDecimal> summaryAmount = extractSummaryAmountAround(lines, index);
                if (summaryAmount.isPresent()) {
                    return summaryAmount;
                }
            }
        }

        for (int index = lines.size() - 1; index >= 0; index--) {
            NormalizedOcrLineResponse line = lines.get(index);
            if (!isStrongSummaryAmountLine(line, index, lines)) {
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
            NormalizedOcrLineResponse line = lines.get(index);
            if (!isPotentialCurrencyCarrier(line, index, lines)) {
                continue;
            }

            Optional<CurrencyCode> explicitCurrency = extractCurrencyFromText(line.normalizedText());
            if (explicitCurrency.isPresent()) {
                return explicitCurrency;
            }
        }

        for (int index = lines.size() - 1; index >= 0; index--) {
            NormalizedOcrLineResponse line = lines.get(index);
            String text = line.normalizedText();
            if (!StringUtils.hasText(text)
                || looksLikePromoOrDiscountLine(text)
                || looksLikeTaxLine(text)
                || looksLikeItemLine(line)
                || looksLikeProductCodeLine(text)) {
                continue;
            }

            Optional<CurrencyCode> explicitCurrency = extractCurrencyFromText(text);
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
        PendingAmount pendingAmount = null;
        int lineIndex = 0;

        for (NormalizedOcrLineResponse line : lines) {
            String text = line.normalizedText();
            if (!StringUtils.hasText(text) || line.ignored()) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                pendingAmount = null;
                continue;
            }

            if (containsTotalKeyword(text) && !looksLikePotentialItemTitle(line)) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                pendingAmount = null;
                break;
            }

            List<DecimalMatch> matches = extractDecimalMatches(text);
            boolean hasLetters = containsLetters(text);

            if (pendingAmount != null
                && hasLetters
                && (looksLikePotentialItemTitle(line) || looksLikeEmbeddedQuantityOnlyLine(text, matches))) {
                ParsedReceiptLineItem item = parseTitleWithPendingAmount(line, pendingAmount, ++lineIndex);
                if (item != null) {
                    items.add(item);
                }
                pendingAmount = null;
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                continue;
            }

            if (!matches.isEmpty() && !hasLetters && StringUtils.hasText(pendingTitle)) {
                ParsedReceiptLineItem item = parsePendingAmountLine(line, matches, pendingTitle, pendingSourceLines, ++lineIndex);
                if (item != null) {
                    items.add(item);
                }
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                pendingAmount = null;
                continue;
            }

            if (looksLikeStandaloneAmountLine(line)) {
                pendingAmount = new PendingAmount(matches.getLast().value(), List.of(sourceText(line)));
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                continue;
            }

            if (shouldIgnoreForLineItems(line)) {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                pendingAmount = null;
                continue;
            }

            if (!matches.isEmpty() && hasLetters) {
                if (looksLikeEmbeddedQuantityOnlyLine(text, matches)) {
                    String normalizedTitle = sanitizeTitle(text);
                    pendingTitle = StringUtils.hasText(pendingTitle)
                        ? sanitizeTitle(pendingTitle + " " + normalizedTitle)
                        : normalizedTitle;
                    pendingSourceLines.add(sourceText(line));
                    pendingAmount = null;
                    continue;
                }

                ParsedReceiptLineItem item = parseInlineLineItem(line, matches, pendingTitle, pendingSourceLines, ++lineIndex);
                if (item != null) {
                    items.add(item);
                    pendingTitle = null;
                    pendingSourceLines = new ArrayList<>();
                    pendingAmount = null;
                    continue;
                }
            }

            if (looksLikePotentialItemTitle(line)) {
                String normalizedTitle = sanitizeTitle(text);
                pendingTitle = StringUtils.hasText(pendingTitle)
                    ? sanitizeTitle(pendingTitle + " " + normalizedTitle)
                    : normalizedTitle;
                pendingSourceLines.add(sourceText(line));
                pendingAmount = null;
            } else {
                pendingTitle = null;
                pendingSourceLines = new ArrayList<>();
                pendingAmount = null;
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

        if (!StringUtils.hasText(title) || !isUsefulItemTitle(title)) {
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
        if (!StringUtils.hasText(title) || !isUsefulItemTitle(title)) {
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

    private ParsedReceiptLineItem parseTitleWithPendingAmount(
        NormalizedOcrLineResponse line,
        PendingAmount pendingAmount,
        int lineIndex
    ) {
        String text = line.normalizedText();
        List<DecimalMatch> matches = extractDecimalMatches(text);
        String title = looksLikeEmbeddedQuantityOnlyLine(text, matches) && !matches.isEmpty()
            ? sanitizeTitle(text.substring(0, matches.getFirst().start()))
            : sanitizeTitle(text);
        if (!StringUtils.hasText(title) || !isUsefulItemTitle(title)) {
            return null;
        }

        QuantityDetails quantityDetails = detectQuantity(
            title,
            text,
            List.of(new DecimalMatch(pendingAmount.amount(), 0, 0))
        );
        List<String> sourceLines = new ArrayList<>(pendingAmount.sourceLines());
        sourceLines.add(sourceText(line));

        return new ParsedReceiptLineItem(
            lineIndex,
            title,
            quantityDetails.quantity(),
            quantityDetails.unit(),
            quantityDetails.unitPrice(),
            pendingAmount.amount(),
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
            || looksLikeDateRange(text)
            || containsTotalKeyword(text)
            || isAccountLike(text)
            || looksLikeAddressOrContactLine(text)
            || looksLikeTaxLine(text)
            || looksLikePromoOrDiscountLine(text)
            || looksLikeServiceOrPaymentLine(text)
            || looksLikeReceiptMetaLine(text)
            || looksLikeProductCodeLine(text)
            || looksLikeStandaloneQuantityOrPriceFragment(text)
            || (line.tags().contains("service_like") && !looksLikeItemLine(line))
            || (line.tags().contains("header_like") && isEarlyHeader(line) && !line.tags().contains("price_like"))
            || text.length() < 2;
    }

    private boolean looksLikePotentialItemTitle(NormalizedOcrLineResponse line) {
        String text = line.normalizedText();
        if (!containsLetters(text)
            || containsMoney(text)
            || containsDateOrTimeMetadata(text)
            || looksLikeDateRange(text)
            || containsTotalKeyword(text)
            || isAccountLike(text)
            || looksLikeAddressOrContactLine(text)
            || looksLikeTaxLine(text)
            || looksLikePromoOrDiscountLine(text)
            || looksLikeServiceOrPaymentLine(text)
            || looksLikeReceiptMetaLine(text)
            || looksLikeProductCodeLine(text)
        ) {
            return false;
        }
        if ((line.tags().contains("header_like") && isEarlyHeader(line))
            || (line.tags().contains("service_like") && !line.tags().contains("content_like"))) {
            return false;
        }

        long letterCount = countLetters(text);
        long digitCount = text.codePoints().filter(Character::isDigit).count();
        return letterCount >= 4
            && digitCount <= Math.max(6, letterCount * 2L)
            && !looksLikeStandaloneQuantityOrPriceFragment(text)
            && isUsefulItemTitle(text);
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
            && !looksLikeAddressOrContactLine(text)
            && !looksLikeServiceOrPaymentLine(text)
            && !looksLikePromoOrDiscountLine(text)
            && !looksLikeTaxLine(text)
            && !looksLikeReceiptMetaLine(text)
            && !looksLikeStandaloneQuantityOrPriceFragment(text)
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
        if (looksLikeServiceOrPaymentLine(title) || looksLikeReceiptMetaLine(title)) {
            score += 20;
        }
        if (title.chars().anyMatch(Character::isDigit)) {
            score += 15;
        }
        if (countLetters(title) < 4 || title.length() < 4) {
            score += 35;
        }
        if (uniqueLetterCount(title) <= 2) {
            score += 25;
        }
        if (title.length() > 40) {
            score += 10;
        }
        if (looksLikeAddressOrContactLine(title)) {
            score += 30;
        }
        if (extractMerchantAlias(title).isPresent()) {
            score -= 25;
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

        Matcher compactMatcher = Pattern.compile("(\\d{2}[./-]\\d{2}[./-]\\d{4})(?=\\d{2}:\\s*\\d{2}|\\b)").matcher(value);
        while (compactMatcher.find()) {
            String candidate = compactMatcher.group(1);
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    return Optional.of(LocalDate.parse(candidate, formatter));
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

    private boolean looksLikeDateRange(String line) {
        return DATE_RANGE_PATTERN.matcher(line).find();
    }

    private boolean containsTotalKeyword(String line) {
        String normalized = normalizeForMatching(line);
        return keywordLexicon.containsSummaryKeyword(normalized);
    }

    private boolean isAccountLike(String line) {
        String normalized = normalizeForMatching(line);
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        boolean ibanLike = normalized.contains("ua") && digitCount >= 10;
        return ibanLike || keywordLexicon.containsAccountKeyword(normalized);
    }

    private boolean isBarcodeLike(String line) {
        return BARCODE_PATTERN.matcher(line.replace('\u00A0', ' ')).matches();
    }

    private boolean looksLikeProductCodeLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }

        String normalized = normalizeForMatching(line);
        if (LONG_DIGIT_PATTERN.matcher(line).find() && countLetters(line) <= 12 && !containsMoney(line)) {
            return true;
        }

        return normalized.contains("штрих")
            || keywordLexicon.containsBarcodeKeyword(normalized)
            || normalized.contains("koa")
            || normalized.contains("k0a");
    }

    private boolean looksLikeReceiptMetaLine(String line) {
        String normalized = normalizeForMatching(line);
        return normalized.contains("чек #")
            || normalized.contains("check #")
            || normalized.contains("yek #")
            || normalized.contains("chek")
            || normalized.contains("kco")
            || normalized.contains("kasa")
            || normalized.contains("каса")
            || normalized.contains("касса");
    }

    private boolean looksLikeServiceOrPaymentLine(String line) {
        String normalized = normalizeForMatching(line);
        return keywordLexicon.containsPaymentKeyword(normalized)
            || MASKED_CARD_PATTERN.matcher(normalized).find()
            || normalized.contains("novus zakaz")
            || normalized.contains("zakaz ua");
    }

    private boolean looksLikeAddressOrContactLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }

        String normalized = normalizeForMatching(line);
        long digitCount = normalized.chars().filter(Character::isDigit).count();
        return ADDRESS_CONTACT_PATTERN.matcher(normalized).find()
            || normalized.startsWith("adress")
            || normalized.startsWith("address")
            || normalized.startsWith("addr")
            || ((normalized.contains("phone") || normalized.contains("tel") || normalized.contains("tc"))
                && digitCount >= 4);
    }

    private boolean looksLikePromoOrDiscountLine(String line) {
        String normalized = normalizeForMatching(line);
        return looksLikeDateRange(line)
            || keywordLexicon.containsPromoKeyword(normalized)
            || normalized.contains("-20")
            || normalized.contains("- 20")
            || normalized.contains("uk-20")
            || normalized.contains("заст")
            || normalized.contains("zacto");
    }

    private boolean looksLikeTaxLine(String line) {
        String normalized = normalizeForMatching(line);
        return normalized.contains("%")
            || keywordLexicon.containsTaxKeyword(normalized)
            || normalized.contains("a=")
            || normalized.contains("пдв");
    }

    private boolean looksLikeStandaloneQuantityOrPriceFragment(String line) {
        String normalized = normalizeWhitespace(line);
        return QUANTITY_PRICE_ONLY_PATTERN.matcher(normalized).matches()
            || (containsMoney(normalized) && countLetters(normalized) <= 2 && normalized.length() <= 16);
    }

    private boolean looksLikeStandaloneAmountLine(NormalizedOcrLineResponse line) {
        String text = line.normalizedText();
        return PURE_AMOUNT_LINE_PATTERN.matcher(text).matches()
            && !containsDateOrTimeMetadata(text)
            && !looksLikePromoOrDiscountLine(text)
            && !looksLikeTaxLine(text);
    }

    private boolean looksLikePaymentSummaryAmountLine(String text) {
        return extractCurrencyFromText(text).isPresent()
            && !containsDateOrTimeMetadata(text)
            && !looksLikePromoOrDiscountLine(text)
            && !looksLikeTaxLine(text)
            && (looksLikeServiceOrPaymentLine(text) || isAccountLike(text));
    }

    private boolean isStrongTotalLabelLine(
        NormalizedOcrLineResponse line,
        int index,
        List<NormalizedOcrLineResponse> lines
    ) {
        String text = line.normalizedText();
        return containsTotalKeyword(text)
            && !looksLikeTaxLine(text)
            && !looksLikePromoOrDiscountLine(text)
            && !looksLikeItemLine(line)
            && !(index > 0 && looksLikePromoOrDiscountLine(lines.get(index - 1).normalizedText()));
    }

    private Optional<BigDecimal> extractSummaryAmountAround(List<NormalizedOcrLineResponse> lines, int anchorIndex) {
        List<BigDecimal> currentAmounts = extractAllDecimals(lines.get(anchorIndex).normalizedText());
        if (!currentAmounts.isEmpty() && !looksLikeTaxLine(lines.get(anchorIndex).normalizedText())) {
            return Optional.of(currentAmounts.getLast());
        }

        for (int offset = 1; offset <= 2; offset++) {
            int previousIndex = anchorIndex - offset;
            if (previousIndex >= 0) {
                Optional<BigDecimal> previousAmount = extractAdjacentSummaryAmount(lines.get(previousIndex), previousIndex, lines);
                if (previousAmount.isPresent()) {
                    return previousAmount;
                }
            }

            int nextIndex = anchorIndex + offset;
            if (nextIndex < lines.size()) {
                Optional<BigDecimal> nextAmount = extractAdjacentSummaryAmount(lines.get(nextIndex), nextIndex, lines);
                if (nextAmount.isPresent()) {
                    return nextAmount;
                }
            }
        }

        return Optional.empty();
    }

    private boolean isStrongSummaryAmountLine(
        NormalizedOcrLineResponse line,
        int index,
        List<NormalizedOcrLineResponse> lines
    ) {
        String text = line.normalizedText();
        if (!StringUtils.hasText(text)
            || line.ignored()
            || !containsMoney(text)
            || containsDateOrTimeMetadata(text)
            || looksLikePromoOrDiscountLine(text)
            || looksLikeTaxLine(text)
            || looksLikeItemLine(line)
        ) {
            return false;
        }

        if (looksLikePaymentSummaryAmountLine(text)) {
            return true;
        }

        if (extractCurrencyFromText(text).isPresent() && !looksLikeProductCodeLine(text)) {
            return true;
        }

        return containsTotalKeyword(text)
            && !(index > 0 && looksLikePromoOrDiscountLine(lines.get(index - 1).normalizedText()));
    }

    private boolean isPotentialCurrencyCarrier(
        NormalizedOcrLineResponse line,
        int index,
        List<NormalizedOcrLineResponse> lines
    ) {
        String text = line.normalizedText();
        return StringUtils.hasText(text)
            && !looksLikePromoOrDiscountLine(text)
            && !looksLikeTaxLine(text)
            && (containsTotalKeyword(text) || looksLikeStandaloneAmountLine(line) || isStrongSummaryAmountLine(line, index, lines));
    }

    private Optional<BigDecimal> extractAdjacentSummaryAmount(
        NormalizedOcrLineResponse line,
        int index,
        List<NormalizedOcrLineResponse> lines
    ) {
        if (isStrongSummaryAmountLine(line, index, lines) || looksLikeStandaloneAmountLine(line)) {
            List<BigDecimal> amounts = extractAllDecimals(line.normalizedText());
            if (!amounts.isEmpty()) {
                return Optional.of(amounts.getLast());
            }
        }

        return Optional.empty();
    }

    private Optional<String> extractMerchantAlias(String value) {
        return keywordLexicon.extractMerchantAlias(value);
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

    private boolean isUsefulItemTitle(String title) {
        String normalized = sanitizeTitle(title);
        return StringUtils.hasText(normalized)
            && countLetters(normalized) >= 4
            && normalized.codePoints().filter(Character::isDigit).count() <= Math.max(4, countLetters(normalized) / 2)
            && !looksLikeAddressOrContactLine(normalized)
            && !looksLikeServiceOrPaymentLine(normalized)
            && !looksLikePromoOrDiscountLine(normalized)
            && !looksLikeTaxLine(normalized)
            && !looksLikeProductCodeLine(normalized)
            && !looksLikeReceiptMetaLine(normalized)
            && !looksLikeStandaloneQuantityOrPriceFragment(normalized)
            && !looksLikeWeakFragmentTitle(normalized);
    }

    private boolean looksLikeStrongMerchantHeader(String title) {
        return StringUtils.hasText(title)
            && countLetters(title) >= 8
            && title.chars().noneMatch(Character::isDigit)
            && !looksLikeReceiptMetaLine(title)
            && !looksLikeServiceOrPaymentLine(title);
    }

    private boolean looksLikeEmbeddedQuantityOnlyLine(String text, List<DecimalMatch> matches) {
        if (matches.size() != 1) {
            return false;
        }

        String afterAmount = text.substring(matches.getFirst().end()).trim();
        return StringUtils.hasText(afterAmount)
            && countLetters(afterAmount) > 0
            && !extractCurrencyFromText(afterAmount).isPresent()
            && !afterAmount.equalsIgnoreCase("A");
    }

    private boolean looksLikeBankLikeDocument(List<NormalizedOcrLineResponse> lines) {
        long bankishLines = lines.stream()
            .map(NormalizedOcrLineResponse::normalizedText)
            .filter(StringUtils::hasText)
            .map(this::normalizeForMatching)
            .filter(text -> keywordLexicon.containsAccountKeyword(text)
                || text.contains("onepayia")
                || text.contains("ukrsib")
                || text.contains("bnp")
                || text.contains("swift")
                || text.contains("mfo")
                || text.contains("iban"))
            .count();
        return bankishLines >= 3;
    }

    private boolean containsRetailItemSignal(List<NormalizedOcrLineResponse> lines) {
        return lines.stream().anyMatch(this::looksLikeItemLine);
    }

    private boolean looksLikeWeakFragmentTitle(String title) {
        List<String> tokens = Pattern.compile("\\s+")
            .splitAsStream(normalizeWhitespace(title))
            .filter(StringUtils::hasText)
            .toList();
        if (tokens.isEmpty()) {
            return true;
        }

        if (tokens.size() == 1) {
            String token = tokens.getFirst();
            long letters = token.codePoints().filter(Character::isLetter).count();
            long digits = token.codePoints().filter(Character::isDigit).count();
            return token.length() <= 3 || (letters <= 3 && digits > 0);
        }

        String first = tokens.getFirst();
        String second = tokens.get(1);
        boolean shortLeadingNoise = first.length() <= 2 && second.length() <= 4;
        boolean digitHeavySecond = second.codePoints().filter(Character::isDigit).count() > 0;
        boolean limitedSignal = countLetters(title) <= 4;
        return shortLeadingNoise && digitHeavySecond && limitedSignal;
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

    private long countLetters(String value) {
        return value.codePoints().filter(Character::isLetter).count();
    }

    private long uniqueLetterCount(String value) {
        return value.toLowerCase(Locale.ROOT)
            .codePoints()
            .filter(Character::isLetter)
            .distinct()
            .count();
    }

    private record DecimalMatch(BigDecimal value, int start, int end) {
    }

    private record QuantityDetails(BigDecimal quantity, String unit, BigDecimal unitPrice) {
    }

    private record QuantityWithUnit(BigDecimal quantity, String unit) {
    }

    private record MultiplierDetails(BigDecimal quantity, BigDecimal unitPrice) {
    }

    private record PendingAmount(BigDecimal amount, List<String> sourceLines) {
    }

    private record MerchantCandidate(NormalizedOcrLineResponse line, String title) {
    }
}
