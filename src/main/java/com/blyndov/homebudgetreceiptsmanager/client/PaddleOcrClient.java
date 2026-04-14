package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.config.OcrClientProperties;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "app.ocr.service", name = "backend", havingValue = "PADDLE")
public class PaddleOcrClient implements OcrClient {

    private final RestClient restClient;

    public PaddleOcrClient(RestClient.Builder restClientBuilder, OcrClientProperties ocrClientProperties) {
        this.restClient = restClientBuilder.baseUrl(ocrClientProperties.getPaddleBaseUrl()).build();
    }

    @Override
    public String extractText(String originalFileName, String contentType, byte[] content) {
        PaddleOcrServiceResponse response = extractResult(originalFileName, contentType, content);
        String rawText = normalize(response.rawText());
        if (StringUtils.hasText(rawText)) {
            return rawText;
        }

        String joinedText = response.lines().stream()
            .map(PaddleOcrLineResponse::text)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n"));

        if (!StringUtils.hasText(joinedText)) {
            throw new IllegalStateException("Paddle OCR service returned an empty response");
        }

        return joinedText;
    }

    public PaddleOcrServiceResponse extractResult(String originalFileName, String contentType, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new NamedByteArrayResource(originalFileName, content), MediaType.parseMediaType(contentType));

        PaddleOcrServiceResponse response = restClient.post()
            .uri("/ocr")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(builder.build())
            .retrieve()
            .body(PaddleOcrServiceResponse.class);

        if (response == null) {
            throw new IllegalStateException("Paddle OCR service returned an empty response");
        }

        List<PaddleOcrLineResponse> lines = response.lines() == null ? List.of() : response.lines();
        List<PaddleOcrNormalizedLineResponse> normalizedLines = response.normalizedLines() == null
            ? List.of()
            : response.normalizedLines();
        return new PaddleOcrServiceResponse(normalize(response.rawText()), lines, normalizedLines);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
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
