package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.service.DetectedOcrProfile;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrLanguageDetector;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptOcrLanguageDetectorTests {

    private final ReceiptOcrLanguageDetector detector = new ReceiptOcrLanguageDetector();

    @Test
    void detectsCyrillicFromScriptAndKeywords() {
        DetectedOcrProfile detected = detector.detect(result("ЧЕК\nДата 2026-04-12\nСума 103.98 грн"));

        assertThat(detected).isNotNull();
        assertThat(detected.profileName()).isEqualTo("cyrillic");
    }

    @Test
    void detectsPolishFromDiacriticsAndRetailKeywords() {
        DetectedOcrProfile detected = detector.detect(result("PARAGON\nData 2026-04-12\nŁącznie 103.98\nSklep"));

        assertThat(detected).isNotNull();
        assertThat(detected.profileName()).isEqualTo("polish");
    }

    @Test
    void detectsGermanFromKeywords() {
        DetectedOcrProfile detected = detector.detect(result("RECHNUNG\nDatum 2026-04-12\nGesamt 103.98"));

        assertThat(detected).isNotNull();
        assertThat(detected.profileName()).isEqualTo("german");
    }

    @Test
    void returnsNullWhenSampleLooksGenericEnglishOnly() {
        DetectedOcrProfile detected = detector.detect(result("STORE\nTOTAL 18.90\nTHANK YOU"));

        assertThat(detected).isNull();
    }

    private OcrExtractionResult result(String rawText) {
        List<OcrExtractionLine> lines = rawText.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .map(line -> new OcrExtractionLine(line, 0.98d, null, null))
            .toList();
        return new OcrExtractionResult(rawText, lines);
    }
}
