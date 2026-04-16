package com.blyndov.homebudgetreceiptsmanager.client;

import java.util.List;

public record OcrExtractionLine(String text, Double confidence, Integer order, List<List<Double>> bbox) {
}
