package com.blyndov.homebudgetreceiptsmanager.dto;

import java.util.List;

public record NormalizedOcrLineResponse(
    String originalText,
    String normalizedText,
    Integer order,
    Double confidence,
    List<List<Double>> bbox,
    List<String> tags,
    boolean ignored
) {
}
