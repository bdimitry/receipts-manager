package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrEngineResponse(
    String name,
    String version,
    String model,
    String language,
    String profile,
    Map<String, Object> config
) {
}
