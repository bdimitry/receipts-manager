package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RawOcrPageResponse(
    Integer pageIndex,
    Map<String, Object> imageSizeBefore,
    Map<String, Object> imageSizeAfter,
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
