package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptReviewStatus;
import java.time.Instant;
import java.util.List;

public record ReceiptCorrectionResponse(
    Long id,
    Long receiptId,
    Long userId,
    ReceiptReviewStatus reviewStatus,
    ReceiptCorrectionSnapshotResponse parsedSnapshot,
    ReceiptCorrectionSnapshotResponse correctedSnapshot,
    List<ReceiptCorrectionFieldDiffResponse> diffs,
    Instant createdAt
) {
}
