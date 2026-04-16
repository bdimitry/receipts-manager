package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLineNormalizationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ReceiptOcrNormalizationCorpusTests {

    private ReceiptOcrLineNormalizationService normalizationService;

    @BeforeEach
    void setUp() {
        normalizationService = new ReceiptOcrLineNormalizationService();
    }

    @Test
    void cleanSyntheticReceiptDoesNotDegrade() {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeLines(
            List.of(
                rawLine("RECEIPT", 0),
                rawLine("Date 2026-04-06", 1),
                rawLine("Coffee 120.50", 2),
                rawLine("TOTAL 210.40", 3)
            )
        );

        assertThat(normalized).extracting(NormalizedOcrLineResponse::normalizedText)
            .containsExactly("RECEIPT", "Date 2026-04-06", "Coffee 120.50", "TOTAL 210.40");
        assertThat(normalized).allMatch(line -> !line.ignored());
    }

    @Test
    void noisyRetailFixtureKeepsNumericFidelityAndFlagsBarcodeNoise() throws IOException {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeLines(linesFromFixture("fixtures/ocr/receipt-cyrillic-noisy.txt"));

        assertThat(normalized.stream().anyMatch(line -> line.normalizedText().contains("212.31") && line.tags().contains("price_like"))).isTrue();
        assertThat(normalized.stream().anyMatch(line -> line.originalText().contains("4820000000000") && line.ignored())).isTrue();
        assertThat(normalized.stream().anyMatch(line -> line.normalizedText().contains("42.50"))).isTrue();
    }

    @Test
    void bankLikeFixtureKeepsDatesAndTotalsButFlagsLongAccountNoise() throws IOException {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeLines(linesFromFixture("fixtures/ocr/bank-like-noisy-lines.txt"));

        assertThat(normalized.stream().anyMatch(line -> line.normalizedText().contains("02.04.2026"))).isTrue();
        assertThat(normalized.stream().anyMatch(line -> line.normalizedText().contains("480.00") && line.tags().contains("price_like"))).isTrue();
        assertThat(normalized.stream().anyMatch(line -> line.originalText().contains("1234567890123456") && line.tags().contains("barcode_like"))).isTrue();
        assertThat(normalized.stream().filter(line -> line.tags().contains("header_like")).count()).isGreaterThan(0);
    }

    private List<OcrExtractionLine> linesFromFixture(String path) throws IOException {
        AtomicInteger order = new AtomicInteger();
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .map(line -> new OcrExtractionLine(line, 0.98d, order.getAndIncrement(), null))
            .toList();
    }

    private OcrExtractionLine rawLine(String text, int order) {
        return new OcrExtractionLine(text, 0.99d, order, null);
    }
}
