package com.blyndov.homebudgetreceiptsmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ocr.service")
public class OcrClientProperties {

    private OcrBackend backend = OcrBackend.TESSERACT;
    private String baseUrl;
    private String tesseractBaseUrl;
    private String paddleBaseUrl;

    public OcrBackend getBackend() {
        return backend;
    }

    public void setBackend(OcrBackend backend) {
        this.backend = backend;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTesseractBaseUrl() {
        if (hasText(tesseractBaseUrl)) {
            return tesseractBaseUrl;
        }
        return baseUrl;
    }

    public void setTesseractBaseUrl(String tesseractBaseUrl) {
        this.tesseractBaseUrl = tesseractBaseUrl;
    }

    public String getPaddleBaseUrl() {
        return paddleBaseUrl;
    }

    public void setPaddleBaseUrl(String paddleBaseUrl) {
        this.paddleBaseUrl = paddleBaseUrl;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
