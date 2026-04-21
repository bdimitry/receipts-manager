package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
import java.util.List;

public record NormalizedOcrDocument(
    String rawText,
    List<ReconstructedOcrLineResponse> reconstructedLines,
    List<NormalizedOcrLineResponse> normalizedLines,
    List<NormalizedOcrLineResponse> parserReadyLines,
    String parserReadyText
) {
}
