package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.config.OcrClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "app.ocr.service", name = "backend", havingValue = "TESSERACT", matchIfMissing = true)
public class TesseractOcrClient implements OcrClient {

    private final RestClient restClient;

    public TesseractOcrClient(RestClient.Builder restClientBuilder, OcrClientProperties ocrClientProperties) {
        this.restClient = restClientBuilder.baseUrl(ocrClientProperties.getTesseractBaseUrl()).build();
    }

    @Override
    public String extractText(String originalFileName, String contentType, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new NamedByteArrayResource(originalFileName, content), MediaType.parseMediaType(contentType));

        OcrExtractResponse response = restClient.post()
            .uri("/ocr/extract")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(builder.build())
            .retrieve()
            .body(OcrExtractResponse.class);

        if (response == null || response.text() == null) {
            throw new IllegalStateException("OCR service returned an empty response");
        }

        return response.text();
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(String filename, byte[] content) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
