package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.service.NormalizedOcrDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrFieldCandidate;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLineNormalizationService;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrCandidateSet;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrParser;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrStructuralReconstructionService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ReceiptOcrParserTests {

    private ReceiptOcrLineNormalizationService normalizationService;
    private ReceiptOcrParser parser;
    private ReceiptOcrStructuralReconstructionService reconstructionService;

    @BeforeEach
    void setUp() {
        ReceiptOcrKeywordLexicon keywordLexicon = new ReceiptOcrKeywordLexicon();
        normalizationService = new ReceiptOcrLineNormalizationService(keywordLexicon);
        parser = new ReceiptOcrParser(keywordLexicon);
        reconstructionService = new ReceiptOcrStructuralReconstructionService(keywordLexicon);
    }

    @Test
    void parserExtractsStructuredDocumentFromCleanSyntheticReceipt() {
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

        ParsedReceiptDocument parsed = parser.parse(document);

        assertThat(parsed.merchantName()).isEqualTo("FRESH MARKET");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("124.90");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(parsed.lineItems()).hasSize(2);

        ParsedReceiptLineItem milk = parsed.lineItems().getFirst();
        assertThat(milk.title()).isEqualTo("Milk");
        assertThat(milk.quantity()).isEqualByComparingTo("2");
        assertThat(milk.unitPrice()).isEqualByComparingTo("42.50");
        assertThat(milk.lineTotal()).isEqualByComparingTo("85.00");
    }

    @Test
    void parserExtractsStoreTotalDateCurrencyAndMultipleLineItemsFromNoisyRetailReceipt() throws IOException {
        String rawText = fixture("fixtures/ocr/receipt-cyrillic-noisy.txt");
        ParsedReceiptDocument parsed = parser.parse(normalizationService.normalizeRawTextDocument(rawText));

        assertThat(parsed.merchantName()).startsWith("СІЛЬПО");
        assertThat(parsed.totalAmount()).isEqualByComparingTo(new BigDecimal("212.31"));
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(parsed.lineItems()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::lineTotal)
            .contains(new BigDecimal("85.00"), new BigDecimal("39.90"), new BigDecimal("84.41"), new BigDecimal("3.00"));
    }

    @Test
    void parserExtractsBaselineFieldsFromBankStyleDocumentWithoutInventingItems() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/bank-like-noisy-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("UkrsibBank");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("480.00");
        assertThat(parsed.lineItems()).isEmpty();
    }

    @Test
    void parserHandlesPdfRenderedSampleWithSplitItemAmountLine() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/pdf-rendered-page-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("COFFEE HOUSE");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("199.00");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(parsed.lineItems()).hasSize(2);
        assertThat(parsed.lineItems().getFirst().title()).isEqualTo("AMERICANO");
        assertThat(parsed.lineItems().getFirst().lineTotal()).isEqualByComparingTo("110.00");
    }

    @Test
    void parserRejectsShortNoisyMerchantAndUsesSummaryAmountInsteadOfDateFragment() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("HOH", 0, List.of("header_like"), false),
            normalizedLine("KCO Kaca 09", 1, List.of("header_like"), false),
            normalizedLine("NOVUS ZAKAZ UA", 2, List.of("content_like"), false),
            normalizedLine("103.98", 3, List.of("price_like", "content_like"), false),
            normalizedLine("Cyma", 4, List.of("content_like"), false),
            normalizedLine("17.33", 5, List.of("price_like", "content_like"), false),
            normalizedLine("12.04.2026 20:41:09", 6, List.of("price_like", "content_like"), false)
        );

        ParsedReceiptDocument parsed = parser.parse(
            new NormalizedOcrDocument(
                "BROKEN RAW OCR",
                List.of(),
                normalizedLines,
                normalizedLines.stream().filter(line -> !line.ignored()).toList(),
                ""
            )
        );

        assertThat(parsed.merchantName()).isEqualTo("NOVUS");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("103.98");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 12));
    }

    @Test
    void parserFixesRegressionForRealReceipt2() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-2-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("NOVUS");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("103.98");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 12));
        assertThat(parsed.lineItems()).hasSize(2);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title)
            .anyMatch(title -> title.contains("Coca"))
            .anyMatch(title -> title.contains("Fanta"));
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::rawFragment)
            .noneMatch(fragment -> fragment.toLowerCase().contains("mastercard"))
            .noneMatch(fragment -> fragment.toLowerCase().contains("kaptka"))
            .noneMatch(fragment -> fragment.toLowerCase().contains("cyma"));
    }

    @Test
    void parserImprovesRealReceipt4WithoutInventingPaymentItemsOrDateAsTotal() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-4-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("NOVUS");
        assertThat(parsed.totalAmount()).isNull();
        assertThat(parsed.currency()).isNull();
        assertThat(parsed.purchaseDate()).isNull();
        assertThat(parsed.lineItems()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title)
            .noneMatch(title -> title.contains("CyHKOM"))
            .noneMatch(title -> title.contains("CyHXOM"))
            .noneMatch(title -> title.startsWith("2. 0r"))
            .noneMatch(title -> title.startsWith("2.' at"))
            .noneMatch(title -> title.contains("UK-208"));
    }

    @Test
    void parserKeepsCleanSyntheticReceipt5TotalsButRejectsAddressLineAsMerchant() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-5-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("CASH RECEIPT");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("84.80");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2018, 1, 1));
        assertThat(parsed.lineItems()).hasSize(7);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title)
            .contains("Ipsum")
            .doesNotContain("CASH RECEIPT", "Adress1234Lorem Lpsum Dolor", "THANK YOU");
    }

    @Test
    void parserRanksFinalTotalAboveSubtotalAndTaxLines() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("CASH RECEIPT", 0, List.of("header_like", "zone_merchant_block"), false),
            normalizedLine("Date 01-01-2018", 1, List.of("header_like", "zone_metadata"), false),
            normalizedLine("Lorem 6.50", 2, List.of("content_like", "price_like", "zone_items"), false),
            normalizedLine("Total 84.80", 3, List.of("price_like", "service_like", "zone_totals"), false),
            normalizedLine("Sub-total 76.80", 4, List.of("price_like", "service_like", "zone_totals"), false),
            normalizedLine("Sales Tax 8.00", 5, List.of("price_like", "service_like", "zone_totals"), false),
            normalizedLine("Balance 84.80", 6, List.of("price_like", "service_like", "zone_totals"), false)
        );

        ParsedReceiptDocument parsed = parser.parse(
            new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "")
        );

        assertThat(parsed.merchantName()).isEqualTo("CASH RECEIPT");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("84.80");
    }

    @Test
    void parserSurfacesBankLikeSummaryAmountForRealReceipt6() throws IOException {
        ParsedReceiptDocument parsed = parser.parse(
            normalizationService.normalizeRawTextDocument(fixture("fixtures/ocr/real-receipt-6-lines.txt"))
        );

        assertThat(parsed.merchantName()).isEqualTo("UkrsibBank");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("5480.00");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.lineItems()).isEmpty();
    }

    @Test
    void parserKeepsBankLikeCompactDateThroughReconstructionPipeline() throws IOException {
        String rawText = fixture("fixtures/ocr/real-receipt-6-lines.txt");
        var extractionResult = new com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult(
            rawText,
            rawText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> new com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine(line, 0.98d, null, null))
                .toList()
        );
        NormalizedOcrDocument document = normalizationService.normalizeDocument(
            reconstructionService.reconstruct(extractionResult)
        );

        ParsedReceiptDocument parsed = parser.parse(document);

        assertThat(parsed.merchantName()).isEqualTo("UkrsibBank");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("5480.00");
        assertThat(parsed.purchaseDate())
            .as("parser-ready text:%n%s", document.parserReadyText())
            .isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.lineItems()).isEmpty();
    }

    @Test
    void parserUsesNormalizedLinesAsPrimaryInputNotRawTextBlob() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("RECEIPT", 0, List.of("header_like"), false),
            normalizedLine("FRESH MARKET", 1, List.of("header_like"), false),
            normalizedLine("Date 2026-03-14", 2, List.of("header_like"), false),
            normalizedLine("Bread 39.90", 3, List.of("price_like", "content_like"), false),
            normalizedLine("TOTAL UAH 39.90", 4, List.of("price_like", "service_like"), false)
        );

        ParsedReceiptDocument parsed = parser.parse(
            new NormalizedOcrDocument(
                "BROKEN RAW OCR THAT SHOULD NOT DRIVE THE PARSER",
                List.of(),
                normalizedLines,
                normalizedLines,
                "BROKEN RAW OCR THAT SHOULD NOT DRIVE THE PARSER"
            )
        );

        assertThat(parsed.merchantName()).isEqualTo("FRESH MARKET");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("39.90");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title).containsExactly("Bread");
    }

    @Test
    void parserCollectsExplainableCandidatesFromZonesAndNormalizedLines() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("FRESH MARKET", 0, List.of("header_like", "zone_merchant_block"), false),
            normalizedLine("Date 2026-03-14", 1, List.of("header_like", "zone_metadata"), false),
            normalizedLine("Bread 39.90", 2, List.of("price_like", "content_like", "zone_items"), false),
            normalizedLine("TOTAL UAH 39.90", 3, List.of("price_like", "service_like", "zone_totals"), false),
            normalizedLine("VISA 39.90 UAH", 4, List.of("price_like", "service_like", "zone_payment"), false),
            normalizedLine("4820000000000", 5, List.of("barcode_like", "zone_service"), true)
        );

        NormalizedOcrDocument document = new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "");
        ReceiptOcrCandidateSet candidates = parser.collectCandidates(document);
        ParsedReceiptDocument parsed = parser.parse(document);

        assertThat(candidates.merchantCandidates().getFirst().normalizedValue()).isEqualTo("FRESH MARKET");
        assertThat(candidates.merchantCandidates().getFirst().sourceZone()).isEqualTo("MERCHANT_BLOCK");
        assertThat(candidates.merchantCandidates().getFirst().scoringReasons()).contains("merchant_zone");
        assertThat(candidates.dateCandidates()).extracting(candidate -> candidate.normalizedValue()).contains("2026-03-14");
        assertThat(candidates.totalAmountCandidates().getFirst().normalizedValue()).isEqualTo("39.90");
        assertThat(candidates.totalAmountCandidates().getFirst().scoringReasons()).contains("summary_label", "totals_zone");
        assertThat(candidates.paymentAmountCandidates()).extracting(candidate -> candidate.sourceZone()).contains("PAYMENT");
        assertThat(candidates.itemRowCandidates()).extracting(candidate -> candidate.normalizedValue()).containsExactly("Bread");
        assertThat(parsed.candidates().totalAmountCandidates().getFirst().scoringReasons()).contains("totals_zone");
    }

    @Test
    void parserRanksStrongTotalCandidateAbovePlainAmountOnlyLine() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("FRESH MARKET", 0, List.of("header_like", "zone_merchant_block"), false),
            normalizedLine("12.04", 1, List.of("price_like", "content_like", "zone_items"), false),
            normalizedLine("Bread 39.90", 2, List.of("price_like", "content_like", "zone_items"), false),
            normalizedLine("TOTAL UAH 39.90", 3, List.of("price_like", "service_like", "zone_totals"), false)
        );

        NormalizedOcrDocument document = new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "");

        assertThat(parser.collectCandidates(document).totalAmountCandidates().getFirst().normalizedValue()).isEqualTo("39.90");
        assertThat(parser.parse(document).totalAmount()).isEqualByComparingTo("39.90");
    }

    @Test
    void parserUsesPaymentAmountCandidateAsSupportWhenSummaryLabelIsWeak() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("UKRSIBBANK", 0, List.of("header_like", "zone_merchant_block"), false),
            normalizedLine("02.04.202619:03", 1, List.of("zone_metadata"), false),
            normalizedLine("VISA 5 480,00 UAH", 2, List.of("price_like", "service_like", "zone_payment"), false)
        );

        ParsedReceiptDocument parsed = parser.parse(
            new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "")
        );

        assertThat(parsed.merchantName()).isEqualTo("UkrsibBank");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("5480.00");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.UAH);
    }

    @Test
    void parserNormalizesOcrDigitConfusionsOnlyInsideNumericCandidates() {
        List<NormalizedOcrLineResponse> normalizedLines = List.of(
            normalizedLine("FOOD ROOM", 0, List.of("header_like", "zone_merchant_block"), false),
            normalizedLine("Date O2.O4.2O26", 1, List.of("header_like", "zone_metadata"), false),
            normalizedLine("Organic OIL 3.20", 2, List.of("content_like", "price_like", "zone_items"), false),
            normalizedLine("TOTAL 1O.5O rpn", 3, List.of("price_like", "service_like", "zone_totals"), false)
        );
        NormalizedOcrDocument document = new NormalizedOcrDocument("RAW", List.of(), normalizedLines, normalizedLines, "");

        ReceiptOcrCandidateSet candidates = parser.collectCandidates(document);
        ParsedReceiptDocument parsed = parser.parse(document);

        assertThat(parsed.totalAmount()).isEqualByComparingTo("10.50");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title).contains("Organic OIL");

        ReceiptOcrFieldCandidate totalCandidate = candidates.totalAmountCandidates().getFirst();
        assertThat(totalCandidate.rawText()).contains("1O.5O");
        assertThat(totalCandidate.normalizedValue()).isEqualTo("10.50");
        assertThat(totalCandidate.normalizationActions()).contains("amount_ocr_digit_correction");

        ReceiptOcrFieldCandidate dateCandidate = candidates.dateCandidates().getFirst();
        assertThat(dateCandidate.rawText()).contains("O2.O4.2O26");
        assertThat(dateCandidate.normalizedValue()).isEqualTo("2026-04-02");
        assertThat(dateCandidate.normalizationActions()).contains("date_ocr_digit_correction");

        assertThat(candidates.merchantCandidates().getFirst().normalizedValue()).isEqualTo("FOOD ROOM");
        assertThat(candidates.merchantCandidates().getFirst().normalizationActions())
            .doesNotContain("amount_ocr_digit_correction", "date_ocr_digit_correction");
        assertThat(candidates.itemRowCandidates().getFirst().normalizedValue()).isEqualTo("Organic OIL");
        assertThat(candidates.itemRowCandidates().getFirst().normalizationActions())
            .doesNotContain("amount_ocr_digit_correction", "date_ocr_digit_correction");
    }

    @Test
    void parserBenefitsFromStructuralReconstructionForDetachedAmountRows() {
        var reconstructed = reconstructionService.reconstruct(
            new com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult(
                "49.99 A\nWTPHX KOA 5449000130389\nHanin ra3.Coca-Co1a 1,75n nET\nHanin ra3.Fanta Orange 1,75n nET 53.99 A\nTOTAL 103.98",
                List.of(
                    rawLineWithBbox("49.99 A", 0, 440, 20, 520, 40),
                    rawLineWithBbox("WTPHX KOA 5449000130389", 1, 40, 44, 520, 62),
                    rawLineWithBbox("Hanin ra3.Coca-Co1a 1,75n nET", 2, 42, 66, 410, 86),
                    rawLineWithBbox("Hanin ra3.Fanta Orange 1,75n nET 53.99 A", 3, 40, 94, 520, 116),
                    rawLineWithBbox("TOTAL 103.98", 4, 40, 150, 280, 170)
                )
            )
        );

        ParsedReceiptDocument parsed = parser.parse(normalizationService.normalizeDocument(reconstructed));

        assertThat(parsed.totalAmount()).isEqualByComparingTo("103.98");
        assertThat(parsed.lineItems()).hasSize(2);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::title)
            .anyMatch(title -> title.contains("Coca"))
            .anyMatch(title -> title.contains("Fanta"));
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::lineTotal)
            .contains(new BigDecimal("49.99"), new BigDecimal("53.99"));
    }

    private String fixture(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private NormalizedOcrLineResponse normalizedLine(String text, int order, List<String> tags, boolean ignored) {
        return new NormalizedOcrLineResponse(text, text, order, 0.99d, null, tags, ignored);
    }

    private com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine rawLineWithBbox(
        String text,
        int order,
        double left,
        double top,
        double right,
        double bottom
    ) {
        return new com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine(
            text,
            0.99d,
            order,
            List.of(
                List.of(left, top),
                List.of(right, top),
                List.of(right, bottom),
                List.of(left, bottom)
            )
        );
    }
}
