package com.blyndov.homebudgetreceiptsmanager.client;

public interface OcrClient {

    String extractText(String originalFileName, String contentType, byte[] content);
}
