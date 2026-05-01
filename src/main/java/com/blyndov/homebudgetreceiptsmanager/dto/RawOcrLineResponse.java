package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RawOcrLineResponse(
    String text,
    Double confidence,
    Integer order,
    List<List<Double>> bbox
) {
}
