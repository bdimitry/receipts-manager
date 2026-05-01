package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class ReceiptOcrCurrencyNormalizer {

    private static final Pattern UAH_PATTERN = Pattern.compile("(?iu)(uah|rph|rpn|tph|toh|reh|teh|\\u0433\\u0440\\u043D|\\u20B4|\\u0420\\u0456\\u0421\\u0452\\u0420\\u0405|\\u0432\\u201A\\u0491)");
    private static final Pattern USD_PATTERN = Pattern.compile("(?iu)(usd|us\\$|\\$)");
    private static final Pattern EUR_PATTERN = Pattern.compile("(?iu)(eur|\\u20AC|\\u0432\\u201A\\u00AC)");
    private static final Pattern RUB_PATTERN = Pattern.compile("(?iu)(rub|\\u20BD|\\u0432\\u201A\\u0405)");

    public Optional<ReceiptOcrFieldNormalizationResult> normalize(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return Optional.empty();
        }

        Optional<CurrencyCode> currency = detect(rawValue);
        if (currency.isEmpty()) {
            return Optional.empty();
        }

        String normalizedToken = currency.orElseThrow().name();
        String action = rawValue.toLowerCase(Locale.ROOT).contains(normalizedToken.toLowerCase(Locale.ROOT))
            ? "currency_code_normalized"
            : "currency_ocr_variant_normalized";
        return Optional.of(new ReceiptOcrFieldNormalizationResult(rawValue, normalizedToken, List.of(action)));
    }

    private Optional<CurrencyCode> detect(String rawValue) {
        if (UAH_PATTERN.matcher(rawValue).find()) {
            return Optional.of(CurrencyCode.UAH);
        }
        if (USD_PATTERN.matcher(rawValue).find()) {
            return Optional.of(CurrencyCode.USD);
        }
        if (EUR_PATTERN.matcher(rawValue).find()) {
            return Optional.of(CurrencyCode.EUR);
        }
        if (RUB_PATTERN.matcher(rawValue).find()) {
            return Optional.of(CurrencyCode.RUB);
        }
        return Optional.empty();
    }
}
