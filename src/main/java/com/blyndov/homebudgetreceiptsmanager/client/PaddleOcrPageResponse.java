package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrPageResponse(
    Integer pageIndex,
    PaddleOcrImageSizeResponse imageSizeBefore,
    PaddleOcrImageSizeResponse imageSizeAfter,
    String strategy,
    List<String> stepsApplied,
    Double upscaleFactor,
    List<Integer> cropBox,
    Boolean deskewApplied,
    Boolean headerRescueApplied,
    String headerRescueStrategy,
    Map<String, Object> imageDiagnosticsBefore,
    Map<String, Object> imageDiagnosticsAfter
) {
}
