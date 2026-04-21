package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
import java.util.List;

public record ReconstructedOcrDocument(
    String rawText,
    List<OcrExtractionLine> originalLines,
    List<ReconstructedOcrLineResponse> reconstructedLines,
    String reconstructedText
) {
}
