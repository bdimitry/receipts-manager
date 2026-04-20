package com.blyndov.homebudgetreceiptsmanager.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReceiptOcrKeywordLexicon {

    private static final Set<String> SUMMARY_KEYWORDS = Set.of(
        "total",
        "subtotal",
        "amount",
        "amount due",
        "balance",
        "sum",
        "suma",
        "cyma",
        "\u0441\u0443\u043c\u0430",
        "\u0441\u0443\u043c\u043c\u0430",
        "\u0438\u0442\u043e\u0433\u043e",
        "\u0432\u0441\u044c\u043e\u0433\u043e",
        "\u0440\u0430\u0437\u043e\u043c",
        "\u0434\u043e \u0441\u043f\u043b\u0430\u0442\u0438",
        "\u043a \u043e\u043f\u043b\u0430\u0442\u0435",
        "\u043e\u043f\u043b\u0430\u0442\u0430"
    );
    private static final Set<String> PAYMENT_KEYWORDS = Set.of(
        "mastercard",
        "visa",
        "terminal",
        "payment",
        "card",
        "transaction",
        "privat",
        "bank",
        "online",
        "trans",
        "auth",
        "aut",
        "system",
        "sistem",
        "sistema",
        "zakaz",
        "kart",
        "kartka",
        "kaptk",
        "kaptka",
        "oplat",
        "master",
        "bezgot",
        "gotiv",
        "gotibo",
        "gotib",
        "otibk",
        "bezgotivkova",
        "be3gotivkova",
        "se3gotibkova",
        "se3rotibkoba",
        "iban",
        "swift",
        "edrpou",
        "account",
        "invoice",
        "\u043f\u043b\u0430\u0442\u0456\u0436",
        "\u043f\u0435\u0440\u0435\u043a\u0430\u0437",
        "\u043a\u0430\u0440\u0442\u043a\u0430",
        "\u043a\u0430\u0440\u0442\u0430",
        "\u0431\u0430\u043d\u043a",
        "\u0442\u0435\u0440\u043c\u0456\u043d\u0430\u043b",
        "\u043e\u043d\u043b\u0430\u0439\u043d"
    );
    private static final Set<String> HEADER_KEYWORDS = Set.of(
        "receipt",
        "cash receipt",
        "document",
        "thank you",
        "store",
        "market",
        "check",
        "chek",
        "kco",
        "kasa",
        "kassa",
        "\u0447\u0435\u043a",
        "\u043a\u0430\u0441\u0430",
        "\u043a\u0430\u0441\u0441\u0430",
        "\u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442",
        "\u043c\u0430\u0433\u0430\u0437\u0438\u043d",
        "\u043c\u0430\u0440\u043a\u0435\u0442",
        "\u0434\u044f\u043a\u0443\u0454\u043c\u043e",
        "\u0441\u043f\u0430\u0441\u0438\u0431\u043e"
    );
    private static final Set<String> BARCODE_KEYWORDS = Set.of(
        "barcode",
        "ean",
        "\u0448\u0442\u0440\u0438\u0445 \u043a\u043e\u0434",
        "\u0448\u0442\u0440\u0438\u0445\u043a\u043e\u0434"
    );
    private static final Set<String> ACCOUNT_KEYWORDS = Set.of(
        "iban",
        "swift",
        "edrpou",
        "account",
        "invoice",
        "\u0454\u0434\u0440\u043f\u043e\u0443",
        "\u0440\u0430\u0445\u0443\u043d\u043e\u043a",
        "\u0440\u0430\u0445\u0443\u043d\u043a\u0443",
        "\u0440\u0430\u0445\u0443\u043d\u043a\u0456\u0432",
        "\u043e\u0442\u0440\u0438\u043c\u0443\u0432\u0430\u0447",
        "\u043e\u0434\u0435\u0440\u0436\u0443\u0432\u0430\u0447",
        "\u0432\u0456\u0434\u043f\u0440\u0430\u0432\u043d\u0438\u043a"
    );
    private static final Set<String> PROMO_KEYWORDS = Set.of(
        "discount",
        "promo",
        "special",
        "spec",
        "spets",
        "cneu",
        "cyhkom",
        "cyhxom",
        "price",
        "cina",
        "zacto",
        "\u0437\u043d\u0438\u0436",
        "\u0441\u043f\u0435\u0446",
        "\u0446\u0456\u043d\u0430",
        "\u0446i\u043d\u0430"
    );
    private static final Set<String> TAX_KEYWORDS = Set.of(
        "vat",
        "pdv",
        "tax",
        "\u043f\u0434\u0432"
    );
    private static final Set<String> GENERIC_HEADERS = Set.of(
        "receipt",
        "cash receipt",
        "document",
        "thank you",
        "\u0434\u044f\u043a\u0443\u0454\u043c\u043e",
        "\u0441\u043f\u0430\u0441\u0438\u0431\u043e"
    );
    private static final Set<String> TRUSTED_MERCHANTS = Set.of(
        "novus",
        "ukrsibbank",
        "fresh market",
        "coffee house"
    );
    private static final Map<Pattern, String> MERCHANT_ALIASES = new LinkedHashMap<>();

    static {
        MERCHANT_ALIASES.put(Pattern.compile("(?iu)\\bn[o0]vus\\b|\\bnoyus\\b|\\bnovus\\b"), "NOVUS");
        MERCHANT_ALIASES.put(Pattern.compile("(?iu)ukrsib\\s*bank|\u0443\u043a\u0440\u0441\u0438\u0431"), "UkrsibBank");
    }

    public boolean containsSummaryKeyword(String text) {
        return containsAny(text, SUMMARY_KEYWORDS);
    }

    public boolean containsPaymentKeyword(String text) {
        return containsAny(text, PAYMENT_KEYWORDS);
    }

    public boolean containsHeaderKeyword(String text) {
        return containsAny(text, HEADER_KEYWORDS);
    }

    public boolean containsBarcodeKeyword(String text) {
        return containsAny(text, BARCODE_KEYWORDS);
    }

    public boolean containsAccountKeyword(String text) {
        return containsAny(text, ACCOUNT_KEYWORDS);
    }

    public boolean containsPromoKeyword(String text) {
        return containsAny(text, PROMO_KEYWORDS);
    }

    public boolean containsTaxKeyword(String text) {
        return containsAny(text, TAX_KEYWORDS);
    }

    public boolean isGenericHeader(String text) {
        return GENERIC_HEADERS.contains(normalizeForMatching(text));
    }

    public boolean isTrustedMerchant(String text) {
        return TRUSTED_MERCHANTS.contains(normalizeForMatching(text));
    }

    public Optional<String> extractMerchantAlias(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }

        return MERCHANT_ALIASES.entrySet().stream()
            .filter(entry -> entry.getKey().matcher(value).find())
            .map(Map.Entry::getValue)
            .findFirst();
    }

    public String normalizeForMatching(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    public boolean containsAny(String text, Set<String> keywords) {
        String normalized = normalizeForMatching(text);
        return keywords.stream().anyMatch(keyword -> containsKeyword(normalized, keyword));
    }

    private boolean containsKeyword(String normalized, String keyword) {
        if (!StringUtils.hasText(normalized) || !StringUtils.hasText(keyword)) {
            return false;
        }

        if (keyword.contains(" ")) {
            return normalized.contains(keyword);
        }

        return Pattern.compile("(^|[^\\p{L}\\d])" + Pattern.quote(keyword) + "([^\\p{L}\\d]|$)")
            .matcher(normalized)
            .find();
    }
}
