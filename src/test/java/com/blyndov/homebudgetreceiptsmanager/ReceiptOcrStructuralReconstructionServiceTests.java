package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrKeywordLexicon;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrStructuralReconstructionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptOcrStructuralReconstructionServiceTests {

    private ReceiptOcrStructuralReconstructionService reconstructionService;

    @BeforeEach
    void setUp() {
        reconstructionService = new ReceiptOcrStructuralReconstructionService(new ReceiptOcrKeywordLexicon());
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

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly(
                "Hanin ra3.Coca-Co1a 1,75n nET 49.99 A",
                "WTPHX KOA 5449000130389",
                "Hanin ra3.Fanta Orange 1,75n nET 53.99 A"
            );
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("title_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("service_like");
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

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("1234567890123", "Cola 1.75L 49.99");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("service_like");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("title_like", "merged");
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

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("KAPTKA", "Cyma 103.98");
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

        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Cyma 103.98", "nAB A=.20.00% 17.33");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("summary_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
    }

    private OcrExtractionLine rawLine(String text, int order, List<List<Double>> bbox) {
        return new OcrExtractionLine(text, 0.98d, order, bbox);
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
