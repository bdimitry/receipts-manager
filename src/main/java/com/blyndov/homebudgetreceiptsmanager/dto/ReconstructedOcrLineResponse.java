package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconstructedOcrLineResponse(
    String text,
    Integer order,
    Double confidence,
    List<List<Double>> bbox,
    OcrLineGeometryResponse geometry,
    OcrDocumentZoneType documentZone,
    List<String> documentZoneReasons,
    List<Integer> sourceOrders,
    List<String> sourceTexts,
    List<String> structuralTags,
    List<String> reconstructionActions
) {
}
