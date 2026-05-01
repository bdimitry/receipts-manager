package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RawOcrArtifactResponse(
    String engineName,
    String engineVersion,
    String engineModelSnapshot,
    String language,
    String profile,
    Boolean preprocessingApplied,
    String preprocessingProfile,
    List<String> preprocessingSteps,
    List<String> preprocessingWarnings,
    Boolean headerRescueApplied,
    List<RawOcrPageResponse> pages,
    List<RawOcrLineResponse> lines,
    String rawText,
    Map<String, Object> diagnostics
) {
}
