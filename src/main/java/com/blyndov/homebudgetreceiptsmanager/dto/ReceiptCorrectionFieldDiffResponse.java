package com.blyndov.homebudgetreceiptsmanager.dto;

public record ReceiptCorrectionFieldDiffResponse(
    String field,
    String parsedValue,
    String correctedValue
) {
}
