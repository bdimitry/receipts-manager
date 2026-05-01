package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrLineGeometryResponse(
    double minX,
    double maxX,
    double minY,
    double maxY,
    double centerX,
    double centerY,
    double width,
    double height
) {
}
