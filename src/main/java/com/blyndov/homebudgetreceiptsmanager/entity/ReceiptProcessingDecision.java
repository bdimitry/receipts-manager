package com.blyndov.homebudgetreceiptsmanager.entity;

public enum ReceiptProcessingDecision {
    PARSED_OK,
    PARSED_LOW_CONFIDENCE,
    NEEDS_REVIEW,
    PARSING_FAILED
}
