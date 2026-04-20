package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.client.OcrRequestOptions;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLanguageDetector;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrRoutingDecision;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrRoutingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceiptOcrRoutingServiceTests {

    @Test
    void userSelectedCountryWinsOverAutoDetection() {
        StubOcrClient stubOcrClient = new StubOcrClient(
            Map.of(
                "en", result("STORE\nTOTAL 210.40"),
                "cyrillic", result("ЧЕК СИЛЬПО\nСУММА 210.40 грн\nДата 2026-04-12")
            )
        );
        ReceiptOcrRoutingService service = new ReceiptOcrRoutingService(stubOcrClient, new ReceiptOcrLanguageDetector());
        Receipt receipt = receiptWithHint(ReceiptCountryHint.UKRAINE);

        ReceiptOcrRoutingDecision decision = service.route(receipt, new byte[] { 1 });

        assertThat(decision.detectionSource()).isEqualTo(OcrLanguageDetectionSource.USER_SELECTED);
        assertThat(decision.ocrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(decision.ocrProfileUsed()).isEqualTo("cyrillic");
        assertThat(stubOcrClient.requestedProfiles()).containsExactly("cyrillic", "en");
    }

    @Test
    void manualUkraineHintStillFallsBackToEnglishWhenCyrillicResultIsFragmented() {
        StubOcrClient stubOcrClient = new StubOcrClient(
            Map.of(
                "en",
                result(
                    """
                    NOVUS
                    12.04.2026
                    Coca-Cola 2L 54.99
                    Fanta Orange 49.99
                    TOTAL 103.98
                    """
                ),
                "cyrillic",
                result(
                    """
                    Kaca
                    12.
                    04.
                    2026
                    Co
                    ca
                    Co
                    la
                    54.
                    99
                    Fa
                    nta
                    49.
                    99
                    """
                )
            )
        );
        ReceiptOcrRoutingService service = new ReceiptOcrRoutingService(stubOcrClient, new ReceiptOcrLanguageDetector());
        Receipt receipt = receiptWithHint(ReceiptCountryHint.UKRAINE);

        ReceiptOcrRoutingDecision decision = service.route(receipt, new byte[] { 1 });

        assertThat(decision.detectionSource()).isEqualTo(OcrLanguageDetectionSource.USER_SELECTED);
        assertThat(decision.ocrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(decision.ocrProfileUsed()).isEqualTo("en");
        assertThat(stubOcrClient.requestedProfiles()).containsExactly("cyrillic", "en");
    }

    @Test
    void autoDetectedProfileIsUsedWhenNoManualHintExists() {
        StubOcrClient stubOcrClient = new StubOcrClient(
            Map.of(
                "en", result("TOTAL 210.40\nгрн\nДата 2026-04-12"),
                "cyrillic", result("ЧЕК\nДата 2026-04-12\nСУММА 210.40 грн")
            )
        );
        ReceiptOcrRoutingService service = new ReceiptOcrRoutingService(stubOcrClient, new ReceiptOcrLanguageDetector());
        Receipt receipt = receiptWithHint(null);

        ReceiptOcrRoutingDecision decision = service.route(receipt, new byte[] { 1 });

        assertThat(decision.detectionSource()).isEqualTo(OcrLanguageDetectionSource.AUTO_DETECTED);
        assertThat(decision.ocrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(decision.ocrProfileUsed()).isEqualTo("cyrillic");
        assertThat(stubOcrClient.requestedProfiles()).containsExactly("en", "cyrillic");
    }

    @Test
    void fallbackProfileIsUsedWhenDetectionIsWeak() {
        StubOcrClient stubOcrClient = new StubOcrClient(
            Map.of("en", result("THANK YOU\nTOTAL 18.90"))
        );
        ReceiptOcrRoutingService service = new ReceiptOcrRoutingService(stubOcrClient, new ReceiptOcrLanguageDetector());
        Receipt receipt = receiptWithHint(null);

        ReceiptOcrRoutingDecision decision = service.route(receipt, new byte[] { 1 });

        assertThat(decision.detectionSource()).isEqualTo(OcrLanguageDetectionSource.DEFAULT_FALLBACK);
        assertThat(decision.ocrProfileStrategy()).isEqualTo("en");
        assertThat(decision.ocrProfileUsed()).isEqualTo("en");
        assertThat(stubOcrClient.requestedProfiles()).containsExactly("en");
    }

    private Receipt receiptWithHint(ReceiptCountryHint receiptCountryHint) {
        Receipt receipt = new Receipt();
        receipt.setOriginalFileName("receipt.png");
        receipt.setContentType("image/png");
        receipt.setReceiptCountryHint(receiptCountryHint);
        return receipt;
    }

    private static OcrExtractionResult result(String rawText) {
        List<OcrExtractionLine> lines = rawText.lines()
            .filter(line -> !line.isBlank())
            .map(line -> new OcrExtractionLine(line, 0.98d, null, null))
            .toList();
        return new OcrExtractionResult(rawText, lines);
    }

    private static final class StubOcrClient implements OcrClient {

        private final Map<String, OcrExtractionResult> resultsByProfile;
        private final List<String> requestedProfiles = new ArrayList<>();

        private StubOcrClient(Map<String, OcrExtractionResult> resultsByProfile) {
            this.resultsByProfile = new HashMap<>(resultsByProfile);
        }

        @Override
        public OcrExtractionResult extractResult(String originalFileName, String contentType, byte[] content) {
            return extractResult(originalFileName, contentType, content, OcrRequestOptions.defaultOptions());
        }

        @Override
        public OcrExtractionResult extractResult(
            String originalFileName,
            String contentType,
            byte[] content,
            OcrRequestOptions options
        ) {
            String profile = options == null || options.profile() == null ? "en" : options.profile();
            requestedProfiles.add(profile);
            return resultsByProfile.get(profile);
        }

        private List<String> requestedProfiles() {
            return requestedProfiles;
        }
    }
}
