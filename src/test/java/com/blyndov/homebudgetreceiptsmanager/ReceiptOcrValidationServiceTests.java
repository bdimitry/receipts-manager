package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptProcessingDecision;
import com.blyndov.homebudgetreceiptsmanager.service.NormalizedOcrDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptValidationResult;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
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
        ReceiptOcrKeywordLexicon keywordLexicon = new ReceiptOcrKeywordLexicon();
        normalizationService = new ReceiptOcrLineNormalizationService(keywordLexicon);
        parser = new ReceiptOcrParser(keywordLexicon);
        validationService = new ReceiptOcrValidationService(keywordLexicon);
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
        assertThat(validationResult.processingDecision()).isEqualTo(ReceiptProcessingDecision.PARSED_OK);
        assertThat(validationResult.confidence().overallReceiptConfidence()).isGreaterThanOrEqualTo(0.85d);
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
            new NormalizedOcrDocument("BROKEN RAW", List.of(), normalizedLines, normalizedLines, "BROKEN RAW"),
            parsedDocument
        );

        assertThat(validationResult.warnings())
            .contains(ReceiptParseWarningCode.SUSPICIOUS_MERCHANT, ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
        assertThat(validationResult.weakParseQuality()).isTrue();
        assertThat(validationResult.processingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(validationResult.confidence().businessConsistencyConfidence()).isLessThan(0.75d);
    }

    @Test
    void missingRequiredFieldsMakeProcessingDecisionNeedsReview() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("FRESH MARKET", 0, List.of("header_like"), false),
            normalizedLine("DATE 2026-04-10", 1, List.of("header_like"), false),
            normalizedLine("TOTAL", 2, List.of("summary_like"), false)
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(
            new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "FRESH MARKET\nDATE 2026-04-10\nTOTAL"),
            new ParsedReceiptDocument("FRESH MARKET", LocalDate.of(2026, 4, 10), null, CurrencyCode.UAH, List.of())
        );

        assertThat(validationResult.warnings()).contains(ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
        assertThat(validationResult.processingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(validationResult.confidence().fieldExtractionConfidence()).isLessThan(0.75d);
    }

    @Test
    void lowOcrConfidenceReducesReceiptConfidenceWithoutInventingWarnings() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("FRESH MARKET", 0, 0.42d, List.of("header_like"), false),
            normalizedLine("DATE 2026-04-10", 1, 0.38d, List.of("header_like"), false),
            normalizedLine("TOTAL 124.90", 2, 0.41d, List.of("summary_like"), false)
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(
            new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "FRESH MARKET\nDATE 2026-04-10\nTOTAL 124.90"),
            new ParsedReceiptDocument("FRESH MARKET", LocalDate.of(2026, 4, 10), new BigDecimal("124.90"), CurrencyCode.UAH, List.of())
        );

        assertThat(validationResult.warnings()).isEmpty();
        assertThat(validationResult.processingDecision()).isEqualTo(ReceiptProcessingDecision.PARSED_LOW_CONFIDENCE);
        assertThat(validationResult.confidence().ocrConfidence()).isLessThan(0.6d);
        assertThat(validationResult.confidence().overallReceiptConfidence()).isLessThan(0.8d);
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
            new NormalizedOcrDocument("RAW", List.of(), List.of(), List.of(), ""),
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
                ReceiptParseWarningCode.NOISY_ITEM_TITLES,
                ReceiptParseWarningCode.SUSPICIOUS_LINE_ITEMS
            )
            .doesNotContain(ReceiptParseWarningCode.SUSPICIOUS_DATE);
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
        ParsedReceiptValidationResult validationResult = validationService.validate(
            document,
            new ParsedReceiptDocument(
                "UkrsibBank",
                LocalDate.of(2026, 4, 2),
                new BigDecimal("5480.00"),
                CurrencyCode.UAH,
                List.of()
            )
        );

        assertThat(validationResult.warnings()).isEmpty();
        assertThat(validationResult.weakParseQuality()).isFalse();
    }

    @Test
    void partialNoisyItemsDoNotAutomaticallyMakeTotalSuspicious() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("NOVUS", 0, List.of("header_like"), false),
            normalizedLine("TOTAL 212.84", 1, List.of("price_like", "service_like"), false),
            normalizedLine("DATE 2026-04-10", 2, List.of("header_like"), false)
        );

        ParsedReceiptDocument parsedDocument = new ParsedReceiptDocument(
            "NOVUS",
            LocalDate.of(2026, 4, 10),
            new BigDecimal("212.84"),
            CurrencyCode.UAH,
            List.of(
                new ParsedReceiptLineItem(1, "Coca Cola Zero", null, null, null, new BigDecimal("50.49"), "Coca Cola Zero 50.49", List.of("Coca Cola Zero 50.49")),
                new ParsedReceiptLineItem(2, "Bag", null, null, null, new BigDecimal("5.99"), "Bag 5.99", List.of("Bag 5.99"))
            )
        );

        ParsedReceiptValidationResult validationResult = validationService.validate(
            new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "NOVUS\nTOTAL 212.84"),
            parsedDocument
        );

        assertThat(validationResult.warnings())
            .doesNotContain(ReceiptParseWarningCode.ITEM_TOTAL_MISMATCH, ReceiptParseWarningCode.SUSPICIOUS_TOTAL);
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private NormalizedOcrLineResponse normalizedLine(String text, int order, List<String> tags, boolean ignored) {
        return normalizedLine(text, order, 0.99d, tags, ignored);
    }

    private NormalizedOcrLineResponse normalizedLine(String text, int order, Double confidence, List<String> tags, boolean ignored) {
        return new NormalizedOcrLineResponse(text, text, order, confidence, null, tags, ignored);
    }
}
