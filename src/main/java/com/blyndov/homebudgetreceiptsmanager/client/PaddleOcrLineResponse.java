package com.blyndov.homebudgetreceiptsmanager.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleOcrLineResponse(String text, Double confidence, Integer order, List<List<Double>> bbox) {
}
