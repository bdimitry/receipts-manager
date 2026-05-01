package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.config.OcrClientProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.RawOcrArtifactResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RawOcrLineResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RawOcrPageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "app.ocr.service", name = "backend", havingValue = "PADDLE", matchIfMissing = true)
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

        return new OcrExtractionResult(rawText, lines, mapRawArtifact(response, rawText, lines));
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

        return response;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private RawOcrArtifactResponse mapRawArtifact(
        PaddleOcrServiceResponse response,
        String rawText,
        List<OcrExtractionLine> lines
    ) {
        PaddleOcrEngineResponse engine = response.engine();
        PaddleOcrPreprocessingResponse preprocessing = response.preprocessing();
        List<PaddleOcrPageResponse> pages = response.pages() == null ? List.of() : response.pages();
        List<String> preprocessingSteps = preprocessing != null && preprocessing.steps() != null
            ? preprocessing.steps()
            : pages.stream()
                .flatMap(page -> page.stepsApplied() == null ? java.util.stream.Stream.<String>empty() : page.stepsApplied().stream())
                .distinct()
                .toList();
        String preprocessingProfile = preprocessing != null && StringUtils.hasText(preprocessing.profile())
            ? preprocessing.profile()
            : pages.stream()
                .map(PaddleOcrPageResponse::strategy)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining("+"));

        return new RawOcrArtifactResponse(
            engine != null && StringUtils.hasText(engine.name()) ? engine.name() : "PaddleOCR",
            engine == null ? null : engine.version(),
            engine == null ? null : engine.model(),
            engine == null ? null : engine.language(),
            engine != null && StringUtils.hasText(engine.profile()) ? engine.profile() : response.profile(),
            preprocessing != null && preprocessing.applied() != null ? preprocessing.applied() : response.preprocessingApplied(),
            StringUtils.hasText(preprocessingProfile) ? preprocessingProfile : null,
            preprocessingSteps,
            preprocessing != null && preprocessing.warnings() != null ? preprocessing.warnings() : List.of(),
            response.headerRescueApplied(),
            pages.stream().map(this::mapRawPage).toList(),
            lines.stream()
                .map(line -> new RawOcrLineResponse(line.text(), line.confidence(), line.order(), line.bbox()))
                .toList(),
            rawText,
            mapDiagnostics(response, engine)
        );
    }

    private RawOcrPageResponse mapRawPage(PaddleOcrPageResponse page) {
        return new RawOcrPageResponse(
            page.pageIndex(),
            mapImageSize(page.imageSizeBefore()),
            mapImageSize(page.imageSizeAfter()),
            page.strategy(),
            page.stepsApplied() == null ? List.of() : page.stepsApplied(),
            page.upscaleFactor(),
            page.cropBox(),
            page.deskewApplied(),
            page.headerRescueApplied(),
            page.headerRescueStrategy(),
            page.imageDiagnosticsBefore(),
            page.imageDiagnosticsAfter()
        );
    }

    private Map<String, Object> mapImageSize(PaddleOcrImageSizeResponse imageSize) {
        if (imageSize == null) {
            return null;
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("width", imageSize.width());
        mapped.put("height", imageSize.height());
        return mapped;
    }

    private Map<String, Object> mapDiagnostics(PaddleOcrServiceResponse response, PaddleOcrEngineResponse engine) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (engine != null && engine.config() != null) {
            diagnostics.put("engineConfig", engine.config());
        }
        if (response.diagnostics() != null) {
            diagnostics.putAll(response.diagnostics());
        }
        return diagnostics.isEmpty() ? null : diagnostics;
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
