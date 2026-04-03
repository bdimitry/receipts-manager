package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrServiceResponse(String rawText, List<PaddleOcrLineResponse> lines) {
}
