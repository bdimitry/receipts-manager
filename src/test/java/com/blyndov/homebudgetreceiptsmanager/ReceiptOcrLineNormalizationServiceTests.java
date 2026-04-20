package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLineNormalizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptOcrLineNormalizationServiceTests {

    private ReceiptOcrLineNormalizationService normalizationService;

    @BeforeEach
    void setUp() {
        normalizationService = new ReceiptOcrLineNormalizationService(new ReceiptOcrKeywordLexicon());
    }

    @Test
    void normalizerCleansWhitespacePunctuationAndSafeMultiplierNoiseWithoutDamagingAmounts() {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeLines(
            List.of(
                rawLine("CASH.RECEIPT", 0),
                rawLine("Dolor.Sit", 1),
                rawLine("0.40,", 2),
                rawLine("2. шт х 104.00.:", 3),
                rawLine("Date 2026-04-06", 4)
            )
        );

        assertThat(normalized.get(0).normalizedText()).isEqualTo("CASH RECEIPT");
        assertThat(normalized.get(1).normalizedText()).isEqualTo("Dolor Sit");
        assertThat(normalized.get(2).normalizedText()).isEqualTo("0.40");
        assertThat(normalized.get(3).normalizedText()).isEqualTo("2. шт x 104.00");
        assertThat(normalized.get(4).normalizedText()).isEqualTo("Date 2026-04-06");
    }

    @Test
    void normalizerFlagsBarcodeNoiseServiceAndHeaderLinesConsistently() {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeLines(
            List.of(
                rawLine("RECEIPT", 0),
                rawLine("1234567890123456", 1),
                rawLine("ШТРИХ КОД 4820000000000", 2),
                rawLine("TOTAL 123.45", 3),
                rawLine("x", 4)
            )
        );

        assertThat(normalized.get(0).tags()).contains("header_like");
        assertThat(normalized.get(1).tags()).contains("barcode_like");
        assertThat(normalized.get(1).ignored()).isTrue();
        assertThat(normalized.get(2).tags()).contains("barcode_like");
        assertThat(normalized.get(2).ignored()).isTrue();
        assertThat(normalized.get(3).tags()).contains("price_like", "service_like");
        assertThat(normalized.get(3).ignored()).isFalse();
        assertThat(normalized.get(4).tags()).contains("noise");
        assertThat(normalized.get(4).ignored()).isTrue();
    }

    @Test
    void normalizeRawTextPreservesOrderAndTraceability() {
        List<NormalizedOcrLineResponse> normalized = normalizationService.normalizeRawText(
            "RECEIPT\nTOTAL 210.40\nTHANK.YOU"
        );

        assertThat(normalized).hasSize(3);
        assertThat(normalized.get(0).originalText()).isEqualTo("RECEIPT");
        assertThat(normalized.get(1).normalizedText()).isEqualTo("TOTAL 210.40");
        assertThat(normalized.get(2).normalizedText()).isEqualTo("THANK YOU");
        assertThat(normalized.get(2).order()).isEqualTo(2);
    }

    @Test
    void normalizationBuildsParserReadyStreamFromNonIgnoredNormalizedLines() {
        var document = normalizationService.normalizeDocument(
            new com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult(
                "RECEIPT\n1234567890123\nTOTAL.. 210.40,\nTHANK.YOU",
                List.of(
                    rawLine("RECEIPT", 0),
                    rawLine("1234567890123", 1),
                    rawLine("TOTAL.. 210.40,", 2),
                    rawLine("THANK.YOU", 3)
                )
            )
        );

        assertThat(document.normalizedLines()).hasSize(4);
        assertThat(document.parserReadyLines()).extracting(NormalizedOcrLineResponse::normalizedText)
            .containsExactly("RECEIPT", "TOTAL. 210.40", "THANK YOU");
        assertThat(document.parserReadyText()).isEqualTo("RECEIPT\nTOTAL. 210.40\nTHANK YOU");
    }

    private OcrExtractionLine rawLine(String text, int order) {
        return new OcrExtractionLine(text, 0.98d, order, null);
    }
}
