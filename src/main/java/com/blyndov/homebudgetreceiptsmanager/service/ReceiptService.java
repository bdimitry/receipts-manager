package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.Purchase;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.exception.CurrencyMismatchException;
import com.blyndov.homebudgetreceiptsmanager.exception.InvalidFileException;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "image/jpeg",
        "image/png",
        "application/pdf"
    );

    private final ReceiptRepository receiptRepository;
    private final AuthService authService;
    private final PurchaseService purchaseService;
    private final S3StorageService s3StorageService;
    private final ReceiptOcrService receiptOcrService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ReceiptService(
        ReceiptRepository receiptRepository,
        AuthService authService,
        PurchaseService purchaseService,
        S3StorageService s3StorageService,
        ReceiptOcrService receiptOcrService,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.receiptRepository = receiptRepository;
        this.authService = authService;
        this.purchaseService = purchaseService;
        this.s3StorageService = s3StorageService;
        this.receiptOcrService = receiptOcrService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public ReceiptResponse uploadReceipt(MultipartFile file, Long purchaseId, CurrencyCode currency, String category) {
        validateFile(file);

        User currentUser = authService.getCurrentAuthenticatedUser();
        Purchase purchase = purchaseId == null ? null : purchaseService.getOwnedPurchaseEntity(purchaseId);
        validatePurchaseCurrency(purchase, currency);
        String normalizedCategory = resolveReceiptCategory(purchase, category);
        String originalFileName = extractOriginalFilename(file);
        String s3Key = buildS3Key(currentUser.getId(), originalFileName);

        log.info(
            "Uploading receipt for userId={}, purchaseId={}, originalFileName={}, contentType={}, size={}",
            currentUser.getId(),
            purchaseId,
            originalFileName,
            file.getContentType(),
            file.getSize()
        );

        s3StorageService.upload(s3Key, file);

        try {
            Receipt receipt = new Receipt();
            receipt.setUser(currentUser);
            receipt.setPurchase(purchase);
            receipt.setOriginalFileName(originalFileName);
            receipt.setContentType(file.getContentType());
            receipt.setFileSize(file.getSize());
            receipt.setCurrency(currency);
            receipt.setCategory(normalizedCategory);
            receipt.setS3Key(s3Key);

            Receipt savedReceipt = receiptRepository.saveAndFlush(receipt);
            log.info(
                "Receipt metadata saved for receiptId={}, userId={}, s3Key={}",
                savedReceipt.getId(),
                currentUser.getId(),
                s3Key
            );
            applicationEventPublisher.publishEvent(new ReceiptUploadedEvent(savedReceipt.getId(), currentUser.getId()));
            return mapToResponse(savedReceipt);
        } catch (RuntimeException exception) {
            s3StorageService.delete(s3Key);
            throw exception;
        }
    }

    public List<ReceiptResponse> getReceipts() {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return receiptRepository.findAllByUser_IdOrderByUploadedAtDescIdDesc(currentUser.getId())
            .stream()
            .map(this::mapToResponse)
            .toList();
    }

    public ReceiptResponse getReceipt(Long id) {
        return mapToResponse(getOwnedReceiptEntity(id));
    }

    public ReceiptOcrResponse getReceiptOcr(Long id) {
        return receiptOcrService.mapToOcrResponse(getOwnedReceiptEntity(id));
    }

    @Transactional(readOnly = true)
    public Receipt getOwnedReceiptEntity(Long id) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return receiptRepository.findByIdAndUser_Id(id, currentUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Uploaded file must not be empty");
        }

        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType) || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidFileException("Unsupported file content type");
        }
    }

    private ReceiptResponse mapToResponse(Receipt receipt) {
        return new ReceiptResponse(
            receipt.getId(),
            receipt.getPurchase() != null ? receipt.getPurchase().getId() : null,
            receipt.getOriginalFileName(),
            receipt.getContentType(),
            receipt.getFileSize(),
            receipt.getCurrency(),
            receipt.getS3Key(),
            receipt.getUploadedAt(),
            receipt.getOcrStatus(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedPurchaseDate(),
            receipt.getLineItems().size(),
            receipt.getOcrErrorMessage(),
            receipt.getOcrProcessedAt(),
            receipt.getCategory()
        );
    }

    private void validatePurchaseCurrency(Purchase purchase, CurrencyCode currency) {
        if (purchase == null || purchase.getCurrency() == currency) {
            return;
        }

        throw new CurrencyMismatchException(
            "Receipt currency must match the linked purchase currency"
        );
    }

    private String buildS3Key(Long userId, String originalFileName) {
        return "receipts/" + userId + "/" + UUID.randomUUID() + "-" + sanitizeFilename(originalFileName);
    }

    private String extractOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            return "file";
        }

        String normalizedFilename = StringUtils.cleanPath(originalFilename).replace("\\", "/");
        int lastSlashIndex = normalizedFilename.lastIndexOf('/');
        return lastSlashIndex >= 0 ? normalizedFilename.substring(lastSlashIndex + 1) : normalizedFilename;
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String resolveReceiptCategory(Purchase purchase, String category) {
        if (purchase != null) {
            return purchase.getCategory();
        }

        if (!StringUtils.hasText(category)) {
            return null;
        }

        return category.trim().toUpperCase(Locale.ROOT);
    }
}
