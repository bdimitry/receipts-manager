package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrServiceResponse(
    String rawText,
    List<PaddleOcrLineResponse> lines,
    String profile,
    Boolean preprocessingApplied,
    Boolean headerRescueApplied,
    PaddleOcrEngineResponse engine,
    PaddleOcrPreprocessingResponse preprocessing,
    List<PaddleOcrPageResponse> pages,
    Map<String, Object> diagnostics
) {
}
