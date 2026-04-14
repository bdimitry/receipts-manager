package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrNormalizedLineResponse(
    String originalText,
    String normalizedText,
    Double confidence,
    Integer order,
    List<List<Double>> bbox,
    List<String> tags,
    Boolean ignored
) {
}
