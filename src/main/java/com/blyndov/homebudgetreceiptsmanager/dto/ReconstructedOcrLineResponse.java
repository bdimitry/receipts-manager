package com.blyndov.homebudgetreceiptsmanager.dto;

import java.util.List;

public record ReconstructedOcrLineResponse(
    String text,
    Integer order,
    Double confidence,
    List<List<Double>> bbox,
    List<Integer> sourceOrders,
    List<String> sourceTexts,
    List<String> structuralTags
) {
}
