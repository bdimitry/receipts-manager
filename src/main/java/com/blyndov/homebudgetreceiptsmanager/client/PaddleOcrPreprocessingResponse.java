package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrPreprocessingResponse(
    Boolean applied,
    String profile,
    List<String> steps,
    List<String> warnings
) {
}
