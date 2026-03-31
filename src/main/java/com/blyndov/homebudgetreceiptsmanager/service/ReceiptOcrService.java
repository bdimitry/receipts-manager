package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptLineItemResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptOcrService.class);

    private final ReceiptRepository receiptRepository;
    private final S3StorageService s3StorageService;
    private final OcrClient ocrClient;
    private final ReceiptOcrParser receiptOcrParser;

    public ReceiptOcrService(
        ReceiptRepository receiptRepository,
        S3StorageService s3StorageService,
        OcrClient ocrClient,
        ReceiptOcrParser receiptOcrParser
    ) {
        this.receiptRepository = receiptRepository;
        this.s3StorageService = s3StorageService;
        this.ocrClient = ocrClient;
        this.receiptOcrParser = receiptOcrParser;
    }

    @Transactional
    public void markProcessing(Long receiptId) {
        Receipt receipt = getReceiptEntity(receiptId);
        receipt.setOcrStatus(ReceiptOcrStatus.PROCESSING);
        receipt.setRawOcrText(null);
        receipt.setParsedStoreName(null);
        receipt.setParsedTotalAmount(null);
        receipt.setParsedPurchaseDate(null);
        receipt.clearLineItems();
        receipt.setOcrErrorMessage(null);
        receipt.setOcrProcessedAt(null);
        receiptRepository.save(receipt);
    }

    @Transactional
    public void process(Long receiptId) {
        Receipt receipt = getReceiptEntity(receiptId);
        byte[] content = s3StorageService.download(receipt.getS3Key());
        String rawText = ocrClient.extractText(receipt.getOriginalFileName(), receipt.getContentType(), content);

        if (!StringUtils.hasText(rawText)) {
            throw new IllegalStateException("OCR returned empty text");
        }

        ReceiptOcrParser.ParsedReceiptData parsedData = receiptOcrParser.parse(rawText);

        receipt.setRawOcrText(rawText);
        receipt.setParsedStoreName(parsedData.parsedStoreName());
        receipt.setParsedTotalAmount(parsedData.parsedTotalAmount());
        receipt.setParsedPurchaseDate(parsedData.parsedPurchaseDate());
        receipt.setLineItems(parsedData.lineItems().stream().map(item -> mapLineItem(receipt, item)).toList());
        receipt.setOcrStatus(ReceiptOcrStatus.DONE);
        receipt.setOcrErrorMessage(null);
        receipt.setOcrProcessedAt(Instant.now());
        receiptRepository.save(receipt);

        log.info(
            "Receipt OCR completed successfully for receiptId={}, userId={}, parsedStoreName={}, parsedTotalAmount={}, parsedPurchaseDate={}, lineItemCount={}",
            receipt.getId(),
            receipt.getUser().getId(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedPurchaseDate(),
            receipt.getLineItems().size()
        );
    }

    @Transactional
    public void markFailed(Long receiptId, String errorMessage) {
        Receipt receipt = getReceiptEntity(receiptId);
        receipt.setOcrStatus(ReceiptOcrStatus.FAILED);
        receipt.setRawOcrText(null);
        receipt.setParsedStoreName(null);
        receipt.setParsedTotalAmount(null);
        receipt.setParsedPurchaseDate(null);
        receipt.clearLineItems();
        receipt.setOcrErrorMessage(errorMessage);
        receipt.setOcrProcessedAt(Instant.now());
        receiptRepository.save(receipt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEnqueueFailed(Long receiptId, String errorMessage) {
        Receipt receipt = getReceiptEntity(receiptId);
        receipt.setOcrStatus(ReceiptOcrStatus.FAILED);
        receipt.setRawOcrText(null);
        receipt.setParsedStoreName(null);
        receipt.setParsedTotalAmount(null);
        receipt.setParsedPurchaseDate(null);
        receipt.clearLineItems();
        receipt.setOcrErrorMessage(errorMessage);
        receipt.setOcrProcessedAt(Instant.now());
        receiptRepository.save(receipt);
        log.error(
            "Receipt OCR queue publishing failed for receiptId={}, userId={}: {}",
            receipt.getId(),
            receipt.getUser().getId(),
            errorMessage
        );
    }

    @Transactional(readOnly = true)
    public Receipt getReceiptEntity(Long receiptId) {
        return receiptRepository.findDetailedById(receiptId)
            .orElseThrow(() -> new ResourceNotFoundException("Receipt not found"));
    }

    @Transactional(readOnly = true)
    public ReceiptOcrResponse mapToOcrResponse(Receipt receipt) {
        return new ReceiptOcrResponse(
            receipt.getId(),
            receipt.getCurrency(),
            receipt.getOcrStatus(),
            receipt.getRawOcrText(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedPurchaseDate(),
            receipt.getLineItems().stream().map(this::mapLineItemResponse).toList(),
            receipt.getOcrErrorMessage(),
            receipt.getOcrProcessedAt()
        );
    }

    private ReceiptLineItem mapLineItem(Receipt receipt, ReceiptOcrParser.ParsedReceiptLineItem parsedLineItem) {
        ReceiptLineItem lineItem = new ReceiptLineItem();
        lineItem.setReceipt(receipt);
        lineItem.setLineIndex(parsedLineItem.lineIndex());
        lineItem.setTitle(parsedLineItem.title());
        lineItem.setQuantity(parsedLineItem.quantity());
        lineItem.setUnit(parsedLineItem.unit());
        lineItem.setUnitPrice(parsedLineItem.unitPrice());
        lineItem.setLineTotal(parsedLineItem.lineTotal());
        lineItem.setRawFragment(parsedLineItem.rawFragment());
        return lineItem;
    }

    private ReceiptLineItemResponse mapLineItemResponse(ReceiptLineItem lineItem) {
        return new ReceiptLineItemResponse(
            lineItem.getId(),
            lineItem.getLineIndex(),
            lineItem.getTitle(),
            lineItem.getQuantity(),
            lineItem.getUnit(),
            lineItem.getUnitPrice(),
            lineItem.getLineTotal(),
            lineItem.getRawFragment()
        );
    }
}
