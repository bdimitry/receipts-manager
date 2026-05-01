package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrAmountNormalizer;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrCurrencyNormalizer;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrDateTimeNormalizer;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrItemTextNormalizer;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrMerchantNormalizer;
import org.junit.jupiter.api.Test;

class ReceiptOcrFieldNormalizersTests {

    @Test
    void amountNormalizerFixesDigitConfusionsInsideAmountCandidates() {
        ReceiptOcrAmountNormalizer normalizer = new ReceiptOcrAmountNormalizer();

        var result = normalizer.normalize("1O.5O");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().normalizedValue()).isEqualTo("10.50");
        assertThat(result.orElseThrow().actions()).contains("amount_ocr_digit_correction");
    }

    @Test
    void amountNormalizerFindsAmountsAttachedToKnownCurrencySuffixes() {
        ReceiptOcrAmountNormalizer normalizer = new ReceiptOcrAmountNormalizer();

        var result = normalizer.findCandidates("Cyma 212.84TPH");

        assertThat(result).singleElement().satisfies(token ->
            assertThat(token.normalized().normalizedValue()).isEqualTo("212.84")
        );
    }

    @Test
    void dateNormalizerFixesDigitConfusionsInsideDateCandidates() {
        ReceiptOcrDateTimeNormalizer normalizer = new ReceiptOcrDateTimeNormalizer();

        var result = normalizer.normalizeDate("O2.O4.2O26");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().normalizedValue()).isEqualTo("2026-04-02");
        assertThat(result.orElseThrow().actions()).contains("date_ocr_digit_correction", "date_iso_normalized");
    }

    @Test
    void currencyNormalizerMapsReceiptCurrencyVariantsToCurrencyCode() {
        ReceiptOcrCurrencyNormalizer normalizer = new ReceiptOcrCurrencyNormalizer();

        var result = normalizer.normalize("TOTAL 10.50 rpn");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().normalizedValue()).isEqualTo("UAH");
        assertThat(result.orElseThrow().actions()).contains("currency_ocr_variant_normalized");
    }

    @Test
    void currencyNormalizerMapsCommonUahOcrLetterConfusionsToCurrencyCode() {
        ReceiptOcrCurrencyNormalizer normalizer = new ReceiptOcrCurrencyNormalizer();

        assertThat(normalizer.normalize("TOTAL 827.80 TPH")).isPresent()
            .get()
            .extracting(result -> result.normalizedValue())
            .isEqualTo("UAH");
        assertThat(normalizer.normalize("Balance 5480.00 TOh")).isPresent()
            .get()
            .extracting(result -> result.normalizedValue())
            .isEqualTo("UAH");
        assertThat(normalizer.normalize("Cyma 212.84 rEH")).isPresent()
            .get()
            .extracting(result -> result.normalizedValue())
            .isEqualTo("UAH");
    }

    @Test
    void merchantNormalizerDoesNotApplyNumericOcrCorrections() {
        ReceiptOcrMerchantNormalizer normalizer = new ReceiptOcrMerchantNormalizer();

        var result = normalizer.normalize("FOOD OIL");

        assertThat(result.normalizedValue()).isEqualTo("FOOD OIL");
        assertThat(result.actions()).doesNotContain("amount_ocr_digit_correction", "date_ocr_digit_correction");
    }

    @Test
    void itemTextNormalizerDoesNotApplyNumericOcrCorrections() {
        ReceiptOcrItemTextNormalizer normalizer = new ReceiptOcrItemTextNormalizer();

        var result = normalizer.normalize("Organic OIL");

        assertThat(result.normalizedValue()).isEqualTo("Organic OIL");
        assertThat(result.actions()).doesNotContain("amount_ocr_digit_correction", "date_ocr_digit_correction");
    }
}
