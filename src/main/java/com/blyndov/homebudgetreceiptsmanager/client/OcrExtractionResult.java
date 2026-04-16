package com.blyndov.homebudgetreceiptsmanager.client;

import java.util.List;

public record OcrExtractionResult(String rawText, List<OcrExtractionLine> lines) {
}
