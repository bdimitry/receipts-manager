package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.NormalizedOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReconstructedOcrLineResponse;
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
import java.util.stream.Collectors;
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
    private static final TypeReference<List<ReconstructedOcrLineResponse>> RECONSTRUCTED_LINES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> WARNING_CODES_TYPE = new TypeReference<>() {
    };

    private final ReceiptRepository receiptRepository;
    private final S3StorageService s3StorageService;
    private final ReceiptOcrRoutingService receiptOcrRoutingService;
    private final ReceiptOcrParser receiptOcrParser;
    private final ReceiptOcrStructuralReconstructionService structuralReconstructionService;
    private final ReceiptOcrLineNormalizationService lineNormalizationService;
    private final ReceiptOcrValidationService receiptOcrValidationService;
    private final ObjectMapper objectMapper;

    public ReceiptOcrService(
        ReceiptRepository receiptRepository,
        S3StorageService s3StorageService,
        ReceiptOcrRoutingService receiptOcrRoutingService,
        ReceiptOcrParser receiptOcrParser,
        ReceiptOcrStructuralReconstructionService structuralReconstructionService,
        ReceiptOcrLineNormalizationService lineNormalizationService,
        ReceiptOcrValidationService receiptOcrValidationService,
        ObjectMapper objectMapper
    ) {
        this.receiptRepository = receiptRepository;
        this.s3StorageService = s3StorageService;
        this.receiptOcrRoutingService = receiptOcrRoutingService;
        this.receiptOcrParser = receiptOcrParser;
        this.structuralReconstructionService = structuralReconstructionService;
        this.lineNormalizationService = lineNormalizationService;
        this.receiptOcrValidationService = receiptOcrValidationService;
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
        receipt.setReconstructedOcrLinesJson(null);
        receipt.setParserReadyText(null);
        receipt.setLanguageDetectionSource(null);
        receipt.setOcrProfileStrategy(null);
        receipt.setOcrProfileUsed(null);
        receipt.setParseWarningsJson(null);
        receipt.setWeakParseQuality(false);
        receipt.clearLineItems();
        receipt.setOcrErrorMessage(null);
        receipt.setOcrProcessedAt(null);
        receiptRepository.save(receipt);
    }

    @Transactional
    public void process(Long receiptId) {
        Receipt receipt = getReceiptEntity(receiptId);
        byte[] content = s3StorageService.download(receipt.getS3Key());
        ReceiptOcrRoutingDecision routingDecision = receiptOcrRoutingService.route(receipt, content);
        var extractionResult = routingDecision.extractionResult();
        String rawText = extractionResult.rawText();

        if (!StringUtils.hasText(rawText)) {
            throw new IllegalStateException("OCR returned empty text");
        }

        ReconstructedOcrDocument reconstructedDocument = structuralReconstructionService.reconstruct(extractionResult);
        NormalizedOcrDocument normalizedDocument = lineNormalizationService.normalizeDocument(reconstructedDocument);
        ParsedReceiptDocument parsedData = receiptOcrParser.parse(normalizedDocument);
        ParsedReceiptValidationResult validationResult = receiptOcrValidationService.validate(normalizedDocument, parsedData);

        receipt.setRawOcrText(rawText);
        receipt.setReconstructedOcrLinesJson(serializeReconstructedLines(reconstructedDocument.reconstructedLines()));
        receipt.setNormalizedOcrLinesJson(serializeNormalizedLines(normalizedDocument.normalizedLines()));
        receipt.setParserReadyText(normalizedDocument.parserReadyText());
        receipt.setLanguageDetectionSource(routingDecision.detectionSource());
        receipt.setOcrProfileStrategy(routingDecision.ocrProfileStrategy());
        receipt.setOcrProfileUsed(routingDecision.ocrProfileUsed());
        receipt.setParseWarningsJson(serializeWarningCodes(validationResult.warnings()));
        receipt.setWeakParseQuality(validationResult.weakParseQuality());
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
            "Receipt OCR completed successfully for receiptId={}, userId={}, strategy={}, profileUsed={}, detectionSource={}, parsedStoreName={}, parsedTotalAmount={}, parsedPurchaseDate={}, parsedCurrency={}, lineItemCount={}, reconstructedLineCount={}, normalizedLineCount={}, parserReadyLineCount={}, warnings={}",
            receipt.getId(),
            receipt.getUser().getId(),
            routingDecision.ocrProfileStrategy(),
            routingDecision.ocrProfileUsed(),
            routingDecision.detectionSource(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedPurchaseDate(),
            parsedData.currency(),
            receipt.getLineItems().size(),
            normalizedDocument.reconstructedLines().size(),
            normalizedDocument.normalizedLines().size(),
            normalizedDocument.parserReadyLines().size(),
            validationResult.warnings().stream().map(Enum::name).collect(Collectors.joining(","))
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
        receipt.setReconstructedOcrLinesJson(null);
        receipt.setParserReadyText(null);
        receipt.setLanguageDetectionSource(null);
        receipt.setOcrProfileStrategy(null);
        receipt.setOcrProfileUsed(null);
        receipt.setParseWarningsJson(null);
        receipt.setWeakParseQuality(false);
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
        receipt.setReconstructedOcrLinesJson(null);
        receipt.setParserReadyText(null);
        receipt.setLanguageDetectionSource(null);
        receipt.setOcrProfileStrategy(null);
        receipt.setOcrProfileUsed(null);
        receipt.setParseWarningsJson(null);
        receipt.setWeakParseQuality(false);
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
        ParsedReceiptDocument persistedParsedDocument = restoreParsedDocument(receipt);
        ParsedReceiptDocument fallbackParsedDocument = shouldReparseForResponse(receipt, normalizedDocument, persistedParsedDocument)
            ? receiptOcrParser.parse(normalizedDocument)
            : persistedParsedDocument;
        ParsedReceiptValidationResult validationResult = restoreValidationResult(receipt, normalizedDocument, fallbackParsedDocument);
        CurrencyCode parsedCurrency = receipt.getParsedCurrency() != null
            ? receipt.getParsedCurrency()
            : fallbackParsedDocument != null ? fallbackParsedDocument.currency() : null;
        return new ReceiptOcrResponse(
            receipt.getId(),
            receipt.getCurrency(),
            receipt.getOcrStatus(),
            receipt.getRawOcrText(),
            normalizedDocument.reconstructedLines(),
            normalizedDocument.normalizedLines(),
            receipt.getReceiptCountryHint(),
            receipt.getLanguageDetectionSource(),
            receipt.getOcrProfileStrategy(),
            receipt.getOcrProfileUsed(),
            receipt.getParsedStoreName(),
            receipt.getParsedTotalAmount(),
            parsedCurrency,
            receipt.getParsedPurchaseDate(),
            receipt.getLineItems().stream().map(this::mapLineItemResponse).toList(),
            validationResult.warnings().stream().map(Enum::name).toList(),
            validationResult.weakParseQuality(),
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

    private String serializeReconstructedLines(List<ReconstructedOcrLineResponse> reconstructedLines) {
        try {
            return objectMapper.writeValueAsString(reconstructedLines);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist reconstructed OCR lines", exception);
        }
    }

    private String serializeWarningCodes(List<ReceiptParseWarningCode> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings.stream().map(Enum::name).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to persist OCR parse warnings", exception);
        }
    }

    private NormalizedOcrDocument restoreNormalizedDocument(Receipt receipt) {
        if (!StringUtils.hasText(receipt.getNormalizedOcrLinesJson())) {
            return lineNormalizationService.normalizeRawTextDocument(receipt.getRawOcrText());
        }

        try {
            List<ReconstructedOcrLineResponse> reconstructedLines = StringUtils.hasText(receipt.getReconstructedOcrLinesJson())
                ? objectMapper.readValue(receipt.getReconstructedOcrLinesJson(), RECONSTRUCTED_LINES_TYPE)
                : List.of();
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
                reconstructedLines,
                normalizedLines,
                parserReadyLines,
                parserReadyText
            );
        } catch (JsonProcessingException exception) {
            log.warn("Failed to deserialize persisted normalized OCR lines for receiptId={}, falling back to raw OCR text", receipt.getId(), exception);
            return lineNormalizationService.normalizeRawTextDocument(receipt.getRawOcrText());
        }
    }

    private ParsedReceiptValidationResult restoreValidationResult(
        Receipt receipt,
        NormalizedOcrDocument normalizedDocument,
        ParsedReceiptDocument parsedDocument
    ) {
        if (receipt.getOcrStatus() != ReceiptOcrStatus.DONE || !StringUtils.hasText(receipt.getRawOcrText())) {
            return new ParsedReceiptValidationResult(List.of(), false);
        }

        if (StringUtils.hasText(receipt.getParseWarningsJson())) {
            try {
                List<ReceiptParseWarningCode> warnings = objectMapper.readValue(receipt.getParseWarningsJson(), WARNING_CODES_TYPE).stream()
                    .map(ReceiptParseWarningCode::valueOf)
                    .toList();
                return new ParsedReceiptValidationResult(warnings, receipt.isWeakParseQuality());
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                log.warn("Failed to deserialize persisted OCR parse warnings for receiptId={}, recomputing validation", receipt.getId(), exception);
            }
        }

        return receiptOcrValidationService.validate(normalizedDocument, parsedDocument);
    }

    private ParsedReceiptDocument restoreParsedDocument(Receipt receipt) {
        return new ParsedReceiptDocument(
            receipt.getParsedStoreName(),
            receipt.getParsedPurchaseDate(),
            receipt.getParsedTotalAmount(),
            receipt.getParsedCurrency(),
            receipt.getLineItems().stream()
                .map(item -> new ParsedReceiptLineItem(
                    item.getLineIndex(),
                    item.getTitle(),
                    item.getQuantity(),
                    item.getUnit(),
                    item.getUnitPrice(),
                    item.getLineTotal(),
                    item.getRawFragment(),
                    item.getRawFragment() == null
                        ? List.of()
                        : List.of(item.getRawFragment().split("\\s*\\|\\s*"))
                ))
                .toList()
        );
    }

    private boolean shouldReparseForResponse(
        Receipt receipt,
        NormalizedOcrDocument normalizedDocument,
        ParsedReceiptDocument parsedDocument
    ) {
        return (receipt.getParsedCurrency() == null
            || (parsedDocument.lineItems().isEmpty() && !normalizedDocument.parserReadyLines().isEmpty()))
            && !normalizedDocument.parserReadyLines().isEmpty()
            && StringUtils.hasText(normalizedDocument.parserReadyText());
    }
}
