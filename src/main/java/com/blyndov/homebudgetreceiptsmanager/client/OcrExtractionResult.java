package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.dto.RawOcrArtifactResponse;
import java.util.List;

public record OcrExtractionResult(String rawText, List<OcrExtractionLine> lines, RawOcrArtifactResponse rawArtifact) {

    public OcrExtractionResult(String rawText, List<OcrExtractionLine> lines) {
        this(rawText, lines, null);
    }
}
