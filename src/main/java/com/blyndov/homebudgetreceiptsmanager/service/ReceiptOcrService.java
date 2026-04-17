package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptLineItemResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ReceiptOcrService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptOcrService.class);
    private static final TypeReference<List<NormalizedOcrLineResponse>> NORMALIZED_LINES_TYPE = new TypeReference<>() {
    };

    private final ReceiptRepository receiptRepository;
    private final S3StorageService s3StorageService;
    private final OcrClient ocrClient;
    private final ReceiptOcrParser receiptOcrParser;
    private final ReceiptOcrLineNormalizationService lineNormalizationService;
    private final ObjectMapper objectMapper;

    public ReceiptOcrService(
        ReceiptRepository receiptRepository,
        S3StorageService s3StorageService,
        OcrClient ocrClient,
        ReceiptOcrParser receiptOcrParser,
        ReceiptOcrLineNormalizationService lineNormalizationService,
        ObjectMapper objectMapper
    ) {
        this.receiptRepository = receiptRepository;
        this.s3StorageService = s3StorageService;
        this.ocrClient = ocrClient;
        this.receiptOcrParser = receiptOcrParser;
        this.lineNormalizationService = lineNormalizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void markProcessing(Long receiptId) {
        Receipt receipt = getReceiptEntity(receiptId);
        receipt.setOcrStatus(ReceiptOcrStatus.PROCESSING);
        receipt.setRawOcrText(null);
        receipt.setParsedStoreName(null);
        receipt.setParsedTotalAmount(null);
        receipt.setParsedCurrency(null);
        receipt.setParsedPurchaseDate(null);
        receipt.setNormalizedOcrLinesJson(null);
        receipt.setParserReadyText(null);
        receipt.clearLineItems();
        receipt.setOcrErrorMessage(null);
        receipt.setOcrProcessedAt(null);
        receiptRepository.save(receipt);
    }

    @Transactional
    public void process(Long receiptId) {
        Receipt receipt = getReceiptEntity(receiptId);
        byte[] content = s3StorageService.download(receipt.getS3Key());
        OcrExtractionResult extractionResult = ocrClient.extractResult(receipt.getOriginalFileName(), receipt.getContentType(), content);
        String rawText = extractionResult.rawText();

        if (!StringUtils.hasText(rawText)) {
            throw new IllegalStateException("OCR returned empty text");
        }

        NormalizedOcrDocument normalizedDocument = lineNormalizationService.normalizeDocument(extractionResult);
        ParsedReceiptDocument parsedData = receiptOcrParser.parse(normalizedDocument);

        receipt.setRawOcrText(rawText);
        receipt.setNormalizedOcrLinesJson(serializeNormalizedLines(normalizedDocument.normalizedLines()));
        receipt.setParserReadyText(normalizedDocument.parserReadyText());
        receipt.setParsedStoreName(parsedData.merchantName());
        receipt.setParsedTotalAmount(parsedData.totalAmount());
        receipt.setParsedCurrency(parsedData.currency());
        receipt.setParsedPurchaseDate(parsedData.purchaseDate());
        receipt.setLineItems(parsedData.lineItems().stream().map(item -> mapLineItem(receipt, item)).toList());
        receipt.setOcrStatus(ReceiptOcrStatus.DONE);
        receipt.setOcrErrorMessage(null);
        receipt.setOcrProcessedAt(Instant.now());
        receiptRepository.save(receipt);

        log.info(
            "Receipt OCR completed successfully for receiptId={}, userId={}, parsedStoreName={}, parsedTotalAmount={}, parsedPurchaseDate={}, parsedCurrency={}, lineItemCount={}, normalizedLineCount={}, parserReadyLineCount={}",
            receipt.getId(),
            receipt.getUser().getId(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedPurchaseDate(),
            parsedData.currency(),
            receipt.getLineItems().size(),
            normalizedDocument.normalizedLines().size(),
            normalizedDocument.parserReadyLines().size()
        );
    }

    @Transactional
    public void markFailed(Long receiptId, String errorMessage) {
        Receipt receipt = getReceiptEntity(receiptId);
        receipt.setOcrStatus(ReceiptOcrStatus.FAILED);
        receipt.setRawOcrText(null);
        receipt.setParsedStoreName(null);
        receipt.setParsedTotalAmount(null);
        receipt.setParsedCurrency(null);
        receipt.setParsedPurchaseDate(null);
        receipt.setNormalizedOcrLinesJson(null);
        receipt.setParserReadyText(null);
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
        receipt.setParsedCurrency(null);
        receipt.setParsedPurchaseDate(null);
        receipt.setNormalizedOcrLinesJson(null);
        receipt.setParserReadyText(null);
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
        NormalizedOcrDocument normalizedDocument = restoreNormalizedDocument(receipt);
        ParsedReceiptDocument fallbackParsedDocument = shouldReparseForResponse(receipt, normalizedDocument)
            ? receiptOcrParser.parse(normalizedDocument)
            : null;
        CurrencyCode parsedCurrency = receipt.getParsedCurrency() != null
            ? receipt.getParsedCurrency()
            : fallbackParsedDocument != null ? fallbackParsedDocument.currency() : null;
        return new ReceiptOcrResponse(
            receipt.getId(),
            receipt.getCurrency(),
            receipt.getOcrStatus(),
            receipt.getRawOcrText(),
            normalizedDocument.normalizedLines(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            parsedCurrency,
            receipt.getParsedPurchaseDate(),
            receipt.getLineItems().stream().map(this::mapLineItemResponse).toList(),
            receipt.getOcrErrorMessage(),
            receipt.getOcrProcessedAt()
        );
    }

    private ReceiptLineItem mapLineItem(Receipt receipt, ParsedReceiptLineItem parsedLineItem) {
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

    private String serializeNormalizedLines(List<NormalizedOcrLineResponse> normalizedLines) {
        try {
            return objectMapper.writeValueAsString(normalizedLines);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist normalized OCR lines", exception);
        }
    }

    private NormalizedOcrDocument restoreNormalizedDocument(Receipt receipt) {
        if (!StringUtils.hasText(receipt.getNormalizedOcrLinesJson())) {
            return lineNormalizationService.normalizeRawTextDocument(receipt.getRawOcrText());
        }

        try {
            List<NormalizedOcrLineResponse> normalizedLines = objectMapper.readValue(
                receipt.getNormalizedOcrLinesJson(),
                NORMALIZED_LINES_TYPE
            );
            List<NormalizedOcrLineResponse> parserReadyLines = normalizedLines.stream()
                .filter(line -> !line.ignored())
                .filter(line -> StringUtils.hasText(line.normalizedText()))
                .toList();
            if (parserReadyLines.isEmpty()) {
                parserReadyLines = normalizedLines.stream()
                    .filter(line -> StringUtils.hasText(line.normalizedText()))
                    .toList();
            }

            String parserReadyText = StringUtils.hasText(receipt.getParserReadyText())
                ? receipt.getParserReadyText()
                : parserReadyLines.stream()
                    .map(NormalizedOcrLineResponse::normalizedText)
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");

            return new NormalizedOcrDocument(
                receipt.getRawOcrText(),
                normalizedLines,
                parserReadyLines,
                parserReadyText
            );
        } catch (JsonProcessingException exception) {
            log.warn("Failed to deserialize persisted normalized OCR lines for receiptId={}, falling back to raw OCR text", receipt.getId(), exception);
            return lineNormalizationService.normalizeRawTextDocument(receipt.getRawOcrText());
        }
    }

    private boolean shouldReparseForResponse(Receipt receipt, NormalizedOcrDocument normalizedDocument) {
        return receipt.getParsedCurrency() == null
            && !normalizedDocument.parserReadyLines().isEmpty()
            && StringUtils.hasText(normalizedDocument.parserReadyText());
    }
}
