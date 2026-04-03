package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrLineResponse(String text, Double confidence) {
}
