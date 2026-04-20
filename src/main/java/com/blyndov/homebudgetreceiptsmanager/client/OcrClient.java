package com.blyndov.homebudgetreceiptsmanager.client;

public interface OcrClient {

    OcrExtractionResult extractResult(String originalFileName, String contentType, byte[] content);

    default OcrExtractionResult extractResult(
        String originalFileName,
        String contentType,
        byte[] content,
        OcrRequestOptions options
    ) {
        return extractResult(originalFileName, contentType, content);
    }

    default String extractText(String originalFileName, String contentType, byte[] content) {
        return extractResult(originalFileName, contentType, content).rawText();
    }
}
