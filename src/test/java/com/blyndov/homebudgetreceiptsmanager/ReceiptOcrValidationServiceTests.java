package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.service.NormalizedOcrDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptValidationResult;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLineNormalizationService;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrParser;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrValidationService;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptParseWarningCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ReceiptOcrValidationServiceTests {

    private ReceiptOcrLineNormalizationService normalizationService;
    private ReceiptOcrParser parser;
    private ReceiptOcrValidationService validationService;

    @BeforeEach
    void setUp() {
        normalizationService = new ReceiptOcrLineNormalizationService();
        parser = new ReceiptOcrParser();
        validationService = new ReceiptOcrValidationService();
    }

    @Test
    void cleanSyntheticReceiptHasNoValidationWarnings() {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(
            """
            RECEIPT
            FRESH MARKET
            Date 2026-03-14
            Milk 2 x 42.50 85.00
            Bread 39.90
            TOTAL UAH 124.90
            """
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(document, parser.parse(document));

        assertThat(validationResult.warnings()).isEmpty();
        assertThat(validationResult.weakParseQuality()).isFalse();
    }

    @Test
    void validationFlagsDateMistakenForTotalAndNoisyMerchantCandidate() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("HOH", 0, List.of("header_like"), false),
            normalizedLine("NOVUS ZAKAZ UA", 1, List.of("content_like"), false),
            normalizedLine("12.04.2026 20:41:09", 2, List.of("content_like"), false),
            normalizedLine("Cyma", 3, List.of("content_like"), false)
        );

        ParsedReceiptDocument parsedDocument = new ParsedReceiptDocument(
            "HOH",
            LocalDate.of(2026, 4, 12),
            new BigDecimal("12.04"),
            CurrencyCode.UAH,
            List.of()
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(
            new NormalizedOcrDocument("BROKEN RAW", normalizedLines, normalizedLines, "BROKEN RAW"),
            parsedDocument
        );

        assertThat(validationResult.warnings())
            .contains(ReceiptParseWarningCode.SUSPICIOUS_MERCHANT, ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    @Test
    void validationFlagsServicePaymentContaminationAndBrokenItemMath() {
        List<ParsedReceiptLineItem> lineItems = List.of(
            new ParsedReceiptLineItem(
                1,
                "MasterCard payment",
                null,
                null,
                null,
                new BigDecimal("103.98"),
                "MasterCard payment | 103.98",
                List.of("MasterCard payment", "103.98")
            ),
            new ParsedReceiptLineItem(
                2,
                "Milk",
                new BigDecimal("2"),
                "pcs",
                new BigDecimal("42.50"),
                new BigDecimal("120.00"),
                "Milk 2 x 42.50 120.00",
                List.of("Milk 2 x 42.50 120.00")
            )
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(
            new NormalizedOcrDocument("RAW", List.of(), List.of(), ""),
            new ParsedReceiptDocument("NOVUS", LocalDate.of(2026, 4, 12), new BigDecimal("103.98"), CurrencyCode.UAH, lineItems)
        );

        assertThat(validationResult.warnings())
            .contains(
                ReceiptParseWarningCode.PAYMENT_CONTENT_IN_ITEMS,
                ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS,
                ReceiptParseWarningCode.INCONSISTENT_ITEM_MATH,
                ReceiptParseWarningCode.ITEM_TOTAL_MISMATCH,
                ReceiptParseWarningCode.SUSPICIOUS_TOTAL
            );
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    @Test
    void realReceipt2RemainsMostlyCleanAfterValidation() throws IOException {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-2-lines.txt"));
        ParsedReceiptValidationResult validationResult = validationService.validate(document, parser.parse(document));

        assertThat(validationResult.warnings()).isEmpty();
        assertThat(validationResult.weakParseQuality()).isFalse();
    }

    @Test
    void noisyReceipt4IsFlaggedAsSuspiciousInsteadOfPretendingParseIsClean() throws IOException {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-4-lines.txt"));
        ParsedReceiptValidationResult validationResult = validationService.validate(document, parser.parse(document));

        assertThat(validationResult.warnings())
            .contains(
                ReceiptParseWarningCode.SUSPICIOUS_TOTAL,
                ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS,
                ReceiptParseWarningCode.NOISY_ITEM_TITLES
            );
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    @Test
    void oldNoisySyntheticReceiptIsFlaggedInsteadOfLookingTrustworthy() throws IOException {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-5-lines.txt"));
        ParsedReceiptValidationResult validationResult = validationService.validate(document, parser.parse(document));

        assertThat(validationResult.warnings())
            .contains(
                ReceiptParseWarningCode.SUSPICIOUS_MERCHANT,
                ReceiptParseWarningCode.SUSPICIOUS_DATE,
                ReceiptParseWarningCode.NOISY_ITEM_TITLES,
                ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS
            );
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    @Test
    void bankLikeDocumentWithInventedRetailItemsWouldBeFlagged() throws IOException {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/bank-like-noisy-lines.txt"));
        ParsedReceiptDocument parsedDocument = new ParsedReceiptDocument(
            "UkrsibBank",
            LocalDate.of(2026, 4, 2),
            new BigDecimal("480.00"),
            CurrencyCode.UAH,
            List.of(
                new ParsedReceiptLineItem(
                    1,
                    "Payment transfer",
                    null,
                    null,
                    null,
                    new BigDecimal("480.00"),
                    "Payment transfer | 480.00",
                    List.of("Payment transfer", "480.00")
                )
            )
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(document, parsedDocument);

        assertThat(validationResult.warnings())
            .contains(ReceiptParseWarningCode.PAYMENT_CONTENT_IN_ITEMS, ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    @Test
    void realBankLikeReceiptWithDateFragmentItemIsFlagged() throws IOException {
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-6-lines.txt"));
        ParsedReceiptValidationResult validationResult = validationService.validate(document, parser.parse(document));

        assertThat(validationResult.warnings())
            .contains(ReceiptParseWarningCode.PAYMENT_CONTENT_IN_ITEMS, ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS);
        assertThat(validationResult.weakParseQuality()).isTrue();
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private NormalizedOcrLineResponse normalizedLine(String text, int order, List<String> tags, boolean ignored) {
        return new NormalizedOcrLineResponse(text, text, order, 0.99d, null, tags, ignored);
    }
}
