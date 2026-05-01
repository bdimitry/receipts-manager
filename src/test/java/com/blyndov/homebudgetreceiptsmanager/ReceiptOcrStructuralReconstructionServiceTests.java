package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.dto.OcrDocumentZoneType;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrDocumentZoneClassifier;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrStructuralReconstructionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptOcrStructuralReconstructionServiceTests {

    private ReceiptOcrStructuralReconstructionService reconstructionService;

    @BeforeEach
    void setUp() {
        ReceiptOcrKeywordLexicon keywordLexicon = new ReceiptOcrKeywordLexicon();
        reconstructionService = new ReceiptOcrStructuralReconstructionService(
            keywordLexicon,
            new ReceiptOcrDocumentZoneClassifier(keywordLexicon)
        );
    }

    @Test
    void reconstructsDetachedAmountAcrossSingleBarcodeLine() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "49.99 A\nWTPHX KOA 5449000130389\nHanin ra3.Coca-Co1a 1,75n nET\nHanin ra3.Fanta Orange 1,75n nET 53.99 A",
                List.of(
                    rawLine("49.99 A", 0, bbox(440, 20, 520, 40)),
                    rawLine("WTPHX KOA 5449000130389", 1, bbox(40, 44, 520, 62)),
                    rawLine("Hanin ra3.Coca-Co1a 1,75n nET", 2, bbox(42, 66, 410, 86)),
                    rawLine("Hanin ra3.Fanta Orange 1,75n nET 53.99 A", 3, bbox(40, 94, 520, 116))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(3);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).contains("49.99").containsIgnoringCase("coca");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("5449000130389");
        assertThat(reconstructed.reconstructedLines().get(2).text()).contains("53.99").containsIgnoringCase("fanta");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("title_like", "merged");
        assertThat(reconstructed.reconstructedLines().getFirst().reconstructionActions())
            .contains("paired_amount_before_title", "merged");
        assertThat(reconstructed.reconstructedLines().getFirst().geometry().minX()).isEqualTo(42.0d);
        assertThat(reconstructed.reconstructedLines().getFirst().geometry().maxX()).isEqualTo(520.0d);
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("service_like");
    }

    @Test
    void normalizesGeometryAndSortsFragmentsByXInsideRow() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "12.00\nMilk",
                List.of(
                    rawLine("12.00", 0, bbox(420, 20, 520, 44)),
                    rawLine("Milk", 1, bbox(40, 22, 180, 46))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line -> {
            assertThat(line.text()).isEqualTo("Milk 12.00");
            assertThat(line.sourceOrders()).containsExactly(1, 0);
            assertThat(line.sourceTexts()).containsExactly("Milk", "12.00");
            assertThat(line.geometry().minX()).isEqualTo(40.0d);
            assertThat(line.geometry().maxX()).isEqualTo(520.0d);
            assertThat(line.geometry().minY()).isEqualTo(20.0d);
            assertThat(line.geometry().maxY()).isEqualTo(46.0d);
            assertThat(line.geometry().centerX()).isEqualTo(280.0d);
            assertThat(line.geometry().centerY()).isEqualTo(33.0d);
            assertThat(line.geometry().width()).isEqualTo(480.0d);
            assertThat(line.geometry().height()).isEqualTo(26.0d);
            assertThat(line.structuralTags()).contains("merged");
            assertThat(line.reconstructionActions()).contains("reordered_by_geometry", "merged");
        });
    }

    @Test
    void mergesSplitTitleAndAmountAcrossAdjacentRows() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Milk 2 x 42.50\n85.00\nBread 39.90",
                List.of(
                    rawLine("Milk 2 x 42.50", 0, bbox(40, 20, 320, 40)),
                    rawLine("85.00", 1, bbox(440, 43, 520, 60)),
                    rawLine("Bread 39.90", 2, bbox(40, 92, 300, 112))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Milk 2 x 42.50 85.00", "Bread 39.90");
    }

    @Test
    void mergesColonSeparatedAmountIntoNearbyItemRow() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Milk 2.5% Organic\n59:99 A",
                List.of(
                    rawLine("Milk 2.5% Organic", 0, bbox(40, 20, 320, 44)),
                    rawLine("59:99 A", 1, bbox(430, 50, 520, 74))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Milk 2.5% Organic 59.99 A");
    }

    @Test
    void recognizesCleanCurrencySuffixesWithoutMojibakeAmountProbe() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "TOTAL\n84.80 \u20B4",
                List.of(
                    rawLine("TOTAL", 0, bbox(40, 20, 180, 44)),
                    rawLine("84.80 \u20B4", 1, bbox(430, 50, 540, 74))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line -> {
            assertThat(line.text()).isEqualTo("TOTAL 84.80 \u20B4");
            assertThat(line.structuralTags()).contains("summary_like", "merged");
            assertThat(line.reconstructionActions()).contains("paired_summary_amount");
        });
    }

    @Test
    void mergesMeasureRowIntoItemAcrossBarcodeNoise() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Milk 2.5% Organic\n350r\n4820178811040\n59:99 A",
                List.of(
                    rawLine("Milk 2.5% Organic", 0, bbox(40, 20, 340, 44)),
                    rawLine("350r", 1, bbox(42, 52, 120, 74)),
                    rawLine("4820178811040", 2, bbox(40, 84, 280, 106)),
                    rawLine("59:99 A", 3, bbox(430, 112, 520, 134))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).isEqualTo("Milk 2.5% Organic 350r 59.99 A");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("4820178811040");
    }

    @Test
    void keepsSummaryLinesSeparatedFromItemRows() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Bread\n39.90\nTOTAL\n124.90",
                List.of(
                    rawLine("Bread", 0, bbox(40, 20, 220, 40)),
                    rawLine("39.90", 1, bbox(430, 44, 520, 60)),
                    rawLine("TOTAL", 2, bbox(40, 88, 180, 108)),
                    rawLine("124.90", 3, bbox(420, 112, 520, 130))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Bread 39.90", "TOTAL 124.90");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like");
        assertThat(reconstructed.reconstructedLines().get(1).documentZone()).isEqualTo(OcrDocumentZoneType.TOTALS);
    }

    @Test
    void assignsDocumentZonesWithoutChangingReconstructedText() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "MArA3NH NOVUS\nKCO Kaca 09\nBread 39.90\nCyma 103.98\nBEZGOTIBKOVA 103.98 rPH KAPTKA\nWTPHX KOA 5449000130389\nTHANK YOU",
                List.of(
                    rawLine("MArA3NH NOVUS", 0, bbox(40, 20, 320, 44)),
                    rawLine("KCO Kaca 09", 1, bbox(40, 72, 240, 96)),
                    rawLine("Bread 39.90", 2, bbox(40, 420, 360, 444)),
                    rawLine("Cyma 103.98", 3, bbox(40, 760, 320, 784)),
                    rawLine("BEZGOTIBKOVA 103.98 rPH KAPTKA", 4, bbox(40, 808, 560, 832)),
                    rawLine("WTPHX KOA 5449000130389", 5, bbox(40, 856, 520, 880)),
                    rawLine("THANK YOU", 6, bbox(40, 1040, 260, 1064))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly(
                "MArA3NH NOVUS",
                "КСО Каса 09",
                "Bread 39.90",
                "Сума 103.98",
                "БЕЗГОТІВКОВА КАРТКА 103.98 грн",
                "Штрих код 5449000130389",
                "THANK YOU"
            );
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.documentZone())
            .containsExactly(
                OcrDocumentZoneType.MERCHANT_BLOCK,
                OcrDocumentZoneType.METADATA,
                OcrDocumentZoneType.ITEMS,
                OcrDocumentZoneType.TOTALS,
                OcrDocumentZoneType.PAYMENT,
                OcrDocumentZoneType.SERVICE,
                OcrDocumentZoneType.FOOTER
            );
        assertThat(reconstructed.reconstructedLines().getFirst().documentZoneReasons())
            .contains("top_position", "merchant_or_header_pattern");
    }

    @Test
    void separatesServiceFragmentsInsideMixedRowWithoutDestroyingItemText() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "1234567890123\nCola 1.75L\n49.99",
                List.of(
                    rawLine("1234567890123", 0, bbox(40, 20, 270, 40)),
                    rawLine("Cola 1.75L", 1, bbox(300, 21, 560, 40)),
                    rawLine("49.99", 2, bbox(700, 22, 790, 40))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).isEqualTo("1234567890123");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("Cola").contains("49.99");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("service_like");
        assertThat(reconstructed.reconstructedLines().getFirst().reconstructionActions())
            .contains("split_amount_segment", "isolated_service");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("title_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).reconstructionActions()).contains("split_amount_segment");
    }

    @Test
    void preservesLowConfidenceLineEvidenceForDebugging() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "FAINT TOTAL 18.00",
                List.of(rawLine("FAINT TOTAL 18.00", 0.42d, 0, bbox(40, 20, 280, 46)))
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line -> {
            assertThat(line.text()).contains("18.00");
            assertThat(line.confidence()).isEqualTo(0.42d);
            assertThat(line.sourceOrders()).containsExactly(0);
            assertThat(line.sourceTexts()).containsExactly("FAINT TOTAL 18.00");
            assertThat(line.structuralTags()).contains("low_confidence");
            assertThat(line.reconstructionActions()).contains("low_confidence_preserved");
            assertThat(line.geometry().minX()).isEqualTo(40.0d);
        });
    }

    @Test
    void pairsAmountThatAppearsBeforeSummaryLabel() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "CARD\n103.98\nCyma",
                List.of(
                    rawLine("KAPTKA", 0, bbox(40, 20, 170, 42)),
                    rawLine("103.98", 1, bbox(420, 48, 520, 70)),
                    rawLine("Cyma", 2, bbox(40, 78, 160, 102))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).isEqualTo("KAPTKA");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
    }

    @Test
    void separatesOverclusteredSummaryAndTaxAmountsByVerticalOffset() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Cyma\n103.98\nnAB A=.20.00%\n17.33",
                List.of(
                    rawLine("Cyma", 0, bbox(40, 120, 150, 150)),
                    rawLine("103.98", 1, bbox(420, 112, 520, 172)),
                    rawLine("nAB A=.20.00%", 2, bbox(240, 190, 420, 222)),
                    rawLine("17.33", 3, bbox(450, 186, 520, 224))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("20.00%").contains("17.33");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("summary_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
    }

    @Test
    void keepsBodyItemPercentLinesOutOfVatCanonicalization() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Honoko 2.6% Novus 870r nnauka 128.97 A",
                List.of(
                    rawLine("Honoko 2.6% Novus 870r nnauka 128.97 A", 0, bbox(40, 20, 520, 44))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line -> {
            assertThat(line.text()).contains("2.6%").contains("128.97");
            assertThat(line.text()).doesNotStartWith("Р В Р’В Р вЂ™Р’В Р В Р Р‹Р РЋРЎСџР В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р РЋРЎС™Р В Р’В Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС›");
        });
    }

    @Test
    void canonicalizesStrongReceiptKeywordsWithoutChangingSourceEvidence() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "WTPHX KOA 5449000130389\nHanin ra3.Coca-Co1a 1,75n nET 49.99 A\nCyma 103.98\nnAB A=.20.00% 17.33\n4EK Ib 000315311 00256",
                List.of(
                    rawLine("WTPHX KOA 5449000130389", 0, bbox(40, 20, 420, 44)),
                    rawLine("Hanin ra3.Coca-Co1a 1,75n nET 49.99 A", 1, bbox(40, 60, 520, 84)),
                    rawLine("Cyma 103.98", 2, bbox(40, 100, 250, 124)),
                    rawLine("nAB A=.20.00% 17.33", 3, bbox(40, 140, 310, 164)),
                    rawLine("4EK Ib 000315311 00256", 4, bbox(40, 180, 330, 204))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(5);
        assertThat(reconstructed.reconstructedLines().get(0).text()).contains("5449000130389");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("49.99").containsIgnoringCase("coca");
        assertThat(reconstructed.reconstructedLines().get(2).text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(3).text()).contains("17.33");
        assertThat(reconstructed.reconstructedLines().get(4).text()).contains("000315311 00256");
        assertThat(reconstructed.reconstructedLines().getFirst().sourceTexts()).containsExactly("WTPHX KOA 5449000130389");
    }

    @Test
    void canonicalizedSummaryRowsPreserveGenericCurrencyEvidence() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Cyma: 5 480,00 rpn",
                List.of(rawLine("Cyma: 5 480,00 rpn", 0, bbox(40, 20, 360, 44)))
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->
            assertThat(line.text()).isEqualTo("\u0421\u0443\u043C\u0430 5480.00 \u0433\u0440\u043D")
        );
    }

    @Test
    void mergesPaymentAmountDescriptorWithCardTailAndCanonicalizesResult() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "GE3rOTIBKOBA 103.98 rPH\nKAPTKA",
                List.of(
                    rawLine("GE3rOTIBKOBA 103.98 rPH", 0, bbox(40, 20, 340, 44)),
                    rawLine("KAPTKA", 1, bbox(40, 52, 170, 74))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->
            assertThat(line.text()).contains("103.98")
        );
    }

    @Test
    void canonicalizesStandalonePaymentLabelWithoutMerchantSpecificMemory() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "Onnara.",
                List.of(rawLine("Onnara.", 0, bbox(40, 20, 160, 44)))
            )
        );

        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->
            assertThat(line.text()).doesNotContainIgnoringCase("onnara")
        );
    }

    @Test
    void splitsHeaderLineWhenLegalEntityTextLeaksIntoFirstAmount() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "TOB HOBYC yKPA 50.49 A\n2.5k\nKCO Kaca 09",
                List.of(
                    rawLine("TOB HOBYC yKPA 50.49 A", 0, bbox(40, 20, 340, 44)),
                    rawLine("2.5k", 1, bbox(40, 56, 120, 80)),
                    rawLine("KCO Kaca 09", 2, bbox(40, 92, 220, 116))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSize(4);
        assertThat(reconstructed.reconstructedLines().get(0).text()).isEqualTo("TOB HOBYC yKPA");
        assertThat(reconstructed.reconstructedLines().get(1).text()).isEqualTo("50.49 A");
        assertThat(reconstructed.reconstructedLines().get(2).text()).isEqualTo("2.5k");
        assertThat(reconstructed.reconstructedLines().get(3).text()).contains("09");
    }

    @Test
    void splitsBankAcquirerTokenAndCanonicalizesPaymentSystemRows() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "S1K70DWE nPHBATGAHK HOBYC yKPAIHA\nROATIRHA CHCTEMA:MasterCard KOATPAH3.*110540009500",
                List.of(
                    rawLine("S1K70DWE nPHBATGAHK HOBYC yKPAIHA", 0, bbox(40, 20, 520, 46)),
                    rawLine("ROATIRHA CHCTEMA:MasterCard KOATPAH3.*110540009500", 1, bbox(40, 60, 720, 88))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("HOBYC") || text.contains("NOVUS") || text.contains("yKPAIHA"))
            .anyMatch(text -> text.equals("S1K70DWE"))
            .anyMatch(text -> text.contains("110540009500"));
    }

    @Test
    void keepsRescuedHeaderBlockUsefulWithoutMemorizingFullAddressTranscript() {
        var reconstructed = reconstructionService.reconstruct(
            new OcrExtractionResult(
                "MArA3NH NOVUS\nKHB AAPHNUbKN PANOH\nTANbHIBCbKA\nTOB \"HOBYC yKPAIHA\nNH 360036026593\nKCO Kaca 09",
                List.of(
                    rawLine("MArA3NH NOVUS", 0, bbox(386, 92, 836, 132)),
                    rawLine("KHB AAPHNUbKN PANOH", 1, bbox(180, 142, 910, 182)),
                    rawLine("TANbHIBCbKA", 2, bbox(260, 192, 760, 232)),
                    rawLine("TOB \"HOBYC yKPAIHA", 3, bbox(250, 242, 780, 282)),
                    rawLine("NH 360036026593", 4, bbox(364, 252, 707, 335)),
                    rawLine("KCO Kaca 09", 5, bbox(409, 309, 662, 386))
                )
            )
        );

        assertThat(reconstructed.reconstructedLines().getFirst().text()).containsIgnoringCase("novus");
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("360036026593"))
            .anyMatch(text -> text.contains("09"));
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .noneMatch(text -> text.contains("Р В Р’В Р Р†Р вЂљРЎСљР В Р’В Р РЋРІР‚в„ўР В Р’В Р вЂ™Р’В Р В Р’В Р РЋРЎС™Р В Р’В Р вЂ™Р’ВР В Р’В Р вЂ™Р’В¦Р В Р’В Р вЂ™Р’В¬Р В Р’В Р РЋРІвЂћСћР В Р’В Р вЂ™Р’ВР В Р’В Р Р†РІР‚С›РЎС› Р В Р’В Р вЂ™Р’В Р В Р’В Р РЋРІР‚в„ўР В Р’В Р Р†РІР‚С›РЎС›Р В Р’В Р РЋРІР‚С”Р В Р’В Р РЋРЎС™"))
            .noneMatch(text -> text.contains("Р В Р’В Р РЋРЎвЂєР В Р’В Р Р†Р вЂљР’В Р В Р’В Р Р†Р вЂљРЎвЂќР В Р’В Р вЂ™Р’В¬Р В Р’В Р РЋРЎС™Р В Р’В Р Р†Р вЂљР’В Р В Р’В Р Р†Р вЂљРІвЂћСћР В Р’В Р В Р вЂ№Р В Р’В Р вЂ™Р’В¬Р В Р’В Р РЋРІвЂћСћР В Р’В Р РЋРІР‚в„ў"));
    }

    private OcrExtractionLine rawLine(String text, int order, List<List<Double>> bbox) {
        return new OcrExtractionLine(text, 0.98d, order, bbox);
    }

    private OcrExtractionLine rawLine(String text, double confidence, int order, List<List<Double>> bbox) {
        return new OcrExtractionLine(text, confidence, order, bbox);
    }

    private List<List<Double>> bbox(double left, double top, double right, double bottom) {
        return List.of(
            List.of(left, top),
            List.of(right, top),
            List.of(right, bottom),
            List.of(left, bottom)
        );
    }
}




