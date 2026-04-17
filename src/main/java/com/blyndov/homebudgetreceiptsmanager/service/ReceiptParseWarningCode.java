package com.blyndov.homebudgetreceiptsmanager.service;

public enum ReceiptParseWarningCode {
    SUSPICIOUS_MERCHANT,
    SUSPICIOUS_TOTAL,
    SUSPICIOUS_DATE,
    SUSPICIOUS_LINE_ITEMS,
    ITEM_TOTAL_MISMATCH,
    PAYMENT_CONTENT_IN_ITEMS,
    NOISY_ITEM_TITLES,
    INCONSISTENT_ITEM_MATH
}
