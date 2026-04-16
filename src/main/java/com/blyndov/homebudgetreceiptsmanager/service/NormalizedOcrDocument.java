package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import java.util.List;

public record NormalizedOcrDocument(
    String rawText,
    List<NormalizedOcrLineResponse> normalizedLines,
    List<NormalizedOcrLineResponse> parserReadyLines,
    String parserReadyText
) {
}
