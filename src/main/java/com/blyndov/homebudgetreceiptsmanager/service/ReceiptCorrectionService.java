package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionFieldDiffResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionLineItemResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionSnapshotResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCorrection;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptReviewStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptCorrectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReceiptCorrectionService {

    private static final TypeReference<ReceiptCorrectionSnapshot> SNAPSHOT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ReceiptCorrectionFieldDiff>> DIFF_TYPE = new TypeReference<>() {
    };

    private final ReceiptCorrectionRepository receiptCorrectionRepository;
    private final ReceiptCorrectionDiffService receiptCorrectionDiffService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReceiptCorrectionResponse submitCorrection(Receipt receipt, User user, ReceiptCorrectionRequest request) {
        ReceiptCorrectionSnapshot parsedSnapshot = receiptCorrectionDiffService.parsedSnapshot(receipt);
        ReceiptCorrectionSnapshot correctedSnapshot = receiptCorrectionDiffService.correctedSnapshot(parsedSnapshot, request);
        List<ReceiptCorrectionFieldDiff> diffs = receiptCorrectionDiffService.diff(parsedSnapshot, correctedSnapshot);
        ReceiptReviewStatus reviewStatus = request.confirmed() && diffs.isEmpty()
            ? ReceiptReviewStatus.CONFIRMED
            : ReceiptReviewStatus.CORRECTED;

        ReceiptCorrection correction = new ReceiptCorrection();
        correction.setReceipt(receipt);
        correction.setUser(user);
        correction.setReviewStatus(reviewStatus);
        correction.setParsedSnapshotJson(serialize(parsedSnapshot, "parsed correction snapshot"));
        correction.setCorrectedSnapshotJson(serialize(correctedSnapshot, "corrected correction snapshot"));
        correction.setCorrectionDiffJson(serialize(diffs, "correction diff"));
        ReceiptCorrection savedCorrection = receiptCorrectionRepository.save(correction);

        receipt.setReviewStatus(reviewStatus);
        receipt.setReviewedAt(Instant.now());
        receipt.setReviewedByUser(user);

        return toResponse(savedCorrection, parsedSnapshot, correctedSnapshot, diffs);
    }

    @Transactional(readOnly = true)
    public Optional<ReceiptCorrectionResponse> findLatestCorrection(Long receiptId) {
        return receiptCorrectionRepository.findFirstByReceipt_IdOrderByCreatedAtDescIdDesc(receiptId)
            .map(this::toResponse);
    }

    private ReceiptCorrectionResponse toResponse(ReceiptCorrection correction) {
        try {
            ReceiptCorrectionSnapshot parsedSnapshot = objectMapper.readValue(correction.getParsedSnapshotJson(), SNAPSHOT_TYPE);
            ReceiptCorrectionSnapshot correctedSnapshot = objectMapper.readValue(correction.getCorrectedSnapshotJson(), SNAPSHOT_TYPE);
            List<ReceiptCorrectionFieldDiff> diffs = objectMapper.readValue(correction.getCorrectionDiffJson(), DIFF_TYPE);
            return toResponse(correction, parsedSnapshot, correctedSnapshot, diffs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize receipt correction", exception);
        }
    }

    private ReceiptCorrectionResponse toResponse(
        ReceiptCorrection correction,
        ReceiptCorrectionSnapshot parsedSnapshot,
        ReceiptCorrectionSnapshot correctedSnapshot,
        List<ReceiptCorrectionFieldDiff> diffs
    ) {
        return new ReceiptCorrectionResponse(
            correction.getId(),
            correction.getReceipt().getId(),
            correction.getUser().getId(),
            correction.getReviewStatus(),
            toSnapshotResponse(parsedSnapshot),
            toSnapshotResponse(correctedSnapshot),
            diffs.stream()
                .map(diff -> new ReceiptCorrectionFieldDiffResponse(diff.field(), diff.parsedValue(), diff.correctedValue()))
                .toList(),
            correction.getCreatedAt()
        );
    }

    private ReceiptCorrectionSnapshotResponse toSnapshotResponse(ReceiptCorrectionSnapshot snapshot) {
        return new ReceiptCorrectionSnapshotResponse(
            snapshot.storeName(),
            snapshot.purchaseDate(),
            snapshot.totalAmount(),
            snapshot.currency(),
            snapshot.items().stream()
                .map(item -> new ReceiptCorrectionLineItemResponse(
                    item.title(),
                    item.quantity(),
                    item.unit(),
                    item.unitPrice(),
                    item.lineTotal()
                ))
                .toList()
        );
    }

    private String serialize(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist " + label, exception);
        }
    }
}
