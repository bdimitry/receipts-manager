package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.service.NormalizedOcrDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptDocument;
import com.blyndov.homebudgetreceiptsmanager.service.ParsedReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLineNormalizationService;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrParser;
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

    @BeforeEach
    void setUp() {
        normalizationService = new ReceiptOcrLineNormalizationService();
        parser = new ReceiptOcrParser();
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
        String rawText = new ClassPathResource("fixtures/ocr/receipt-cyrillic-noisy.txt")
            .getContentAsString(StandardCharsets.UTF_8);
        NormalizedOcrDocument document = normalizationService.normalizeRawTextDocument(rawText);

        ParsedReceiptDocument parsed = parser.parse(document);

        assertThat(parsed.merchantName()).isEqualTo(document.normalizedLines().getFirst().normalizedText());
        assertThat(parsed.totalAmount()).isEqualByComparingTo(new BigDecimal("212.31"));
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(parsed.currency()).isNull();
        assertThat(parsed.lineItems()).hasSizeGreaterThan(1);
        assertThat(parsed.lineItems()).extracting(ParsedReceiptLineItem::lineTotal)
            .contains(new BigDecimal("85.00"), new BigDecimal("39.90"), new BigDecimal("84.41"), new BigDecimal("3.00"));
        assertThat(parsed.lineItems().stream().filter(item -> item.quantity() != null).map(ParsedReceiptLineItem::quantity))
            .contains(new BigDecimal("2"), new BigDecimal("1.245"));
        assertThat(parsed.lineItems().stream().filter(item -> item.unit() != null).map(ParsedReceiptLineItem::unit))
            .contains("кг");
        assertThat(parsed.lineItems().stream().map(ParsedReceiptLineItem::sourceLines))
            .allMatch(lines -> lines != null && !lines.isEmpty());
    }

    @Test
    void parserExtractsBaselineFieldsFromBankStyleDocumentWithoutInventingItems() throws IOException {
        String rawText = new ClassPathResource("fixtures/ocr/bank-like-noisy-lines.txt")
            .getContentAsString(StandardCharsets.UTF_8);

        ParsedReceiptDocument parsed = parser.parse(normalizationService.normalizeRawTextDocument(rawText));

        assertThat(parsed.merchantName()).isEqualTo("UkrsibBank");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("480.00");
        assertThat(parsed.lineItems()).isEmpty();
    }

    @Test
    void parserHandlesPdfRenderedSampleWithSplitItemAmountLine() throws IOException {
        String rawText = new ClassPathResource("fixtures/ocr/pdf-rendered-page-lines.txt")
            .getContentAsString(StandardCharsets.UTF_8);

        ParsedReceiptDocument parsed = parser.parse(normalizationService.normalizeRawTextDocument(rawText));

        assertThat(parsed.merchantName()).isEqualTo("COFFEE HOUSE");
        assertThat(parsed.purchaseDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(parsed.totalAmount()).isEqualByComparingTo("199.00");
        assertThat(parsed.currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(parsed.lineItems()).hasSize(2);
        assertThat(parsed.lineItems().getFirst().title()).isEqualTo("AMERICANO");
        assertThat(parsed.lineItems().getFirst().lineTotal()).isEqualByComparingTo("110.00");
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

    private NormalizedOcrLineResponse normalizedLine(String text, int order, List<String> tags, boolean ignored) {
        return new NormalizedOcrLineResponse(text, text, order, 0.99d, null, tags, ignored);
    }
}
