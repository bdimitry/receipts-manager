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
    public OcrExtractionResult extractResult(String originalFileName, String contentType, byte[] content) {
        return extractResult(originalFileName, contentType, content, OcrRequestOptions.defaultOptions());
    }

    @Override
    public OcrExtractionResult extractResult(
        String originalFileName,
        String contentType,
        byte[] content,
        OcrRequestOptions options
    ) {
        PaddleOcrServiceResponse response = extractPaddleResponse(originalFileName, contentType, content, options);
        String rawText = normalize(response.rawText());
        List<OcrExtractionLine> lines = response.lines() == null
            ? List.of()
            : response.lines().stream()
                .map(line -> new OcrExtractionLine(line.text(), line.confidence(), line.order(), line.bbox()))
                .toList();

        if (!StringUtils.hasText(rawText)) {
            rawText = lines.stream()
                .map(OcrExtractionLine::text)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
        }

        if (!StringUtils.hasText(rawText)) {
            throw new IllegalStateException("Paddle OCR service returned an empty response");
        }

        return new OcrExtractionResult(rawText, lines);
    }

    public PaddleOcrServiceResponse extractPaddleResponse(String originalFileName, String contentType, byte[] content) {
        return extractPaddleResponse(originalFileName, contentType, content, OcrRequestOptions.defaultOptions());
    }

    public PaddleOcrServiceResponse extractPaddleResponse(
        String originalFileName,
        String contentType,
        byte[] content,
        OcrRequestOptions options
    ) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new NamedByteArrayResource(originalFileName, content), MediaType.parseMediaType(contentType));

        PaddleOcrServiceResponse response = restClient.post()
            .uri(uriBuilder -> {
                uriBuilder.path("/ocr");
                if (options != null && StringUtils.hasText(options.profile())) {
                    uriBuilder.queryParam("profile", options.profile());
                }
                return uriBuilder.build();
            })
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(builder.build())
            .retrieve()
            .body(PaddleOcrServiceResponse.class);

        if (response == null) {
            throw new IllegalStateException("Paddle OCR service returned an empty response");
        }

        List<PaddleOcrLineResponse> lines = response.lines() == null ? List.of() : response.lines();
        return new PaddleOcrServiceResponse(normalize(response.rawText()), lines);
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
