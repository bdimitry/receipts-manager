package com.blyndov.homebudgetreceiptsmanager.service;

public record ReceiptCorrectionFieldDiff(
    String field,
    String parsedValue,
    String correctedValue
) {
}
