package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.OcrDocumentZoneType;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptProcessingDecision;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptReviewStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=true",
        "app.receipts.ocr.consumer.poll-delay-ms=100",
        "app.receipts.ocr.consumer.wait-time-seconds=1",
        "aws.sqs.receipt-ocr-queue-name=receipt-ocr-queue-persistence-test"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiptOcrPersistenceIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AwsProperties awsProperties;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private SqsClient sqsClient;

    @MockBean
    private OcrClient ocrClient;

    @BeforeEach
    void setUp() throws Exception {
        ensureOcrQueueExists();
        receiptRepository.deleteAll();
        userRepository.deleteAll();
        clearBucket();
        drainOcrQueue();
        String rawText = new ClassPathResource("fixtures/ocr/receipt-cyrillic-noisy.txt").getContentAsString(StandardCharsets.UTF_8);
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(mapResult(rawText));
    }

    @Test
    void ocrFlowPersistsLineItemsAndReturnsThemViaApi() {
        String accessToken = registerAndLogin(uniqueEmail("ocr"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.png", MediaType.IMAGE_PNG, "png".getBytes(StandardCharsets.UTF_8), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody()).isNotNull();
        assertThat(uploadResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getRawOcrArtifactJson()).isNotBlank();
        assertThat(processedReceipt.getReconstructedOcrLinesJson()).isNotBlank();
        assertThat(processedReceipt.getNormalizedOcrLinesJson()).isNotBlank();
        assertThat(processedReceipt.getParserReadyText()).isNotBlank();
        assertThat(processedReceipt.getReceiptCountryHint()).isNull();
        assertThat(processedReceipt.getLanguageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.AUTO_DETECTED);
        assertThat(processedReceipt.getOcrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(processedReceipt.getOcrProfileUsed()).isEqualTo("cyrillic");
        assertThat(processedReceipt.getParseWarningsJson()).isEqualTo("[]");
        assertThat(processedReceipt.isWeakParseQuality()).isFalse();
        assertThat(processedReceipt.getOcrConfidenceJson()).isNotBlank();
        assertThat(processedReceipt.getOcrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.PARSED_OK);
        assertThat(processedReceipt.getReviewStatus()).isEqualTo(ReceiptReviewStatus.UNREVIEWED);
        assertThat(processedReceipt.getLineItems()).hasSizeGreaterThan(1);
        assertThat(processedReceipt.getLineItems())
            .extracting(item -> item.getTitle())
            .contains("МОЛОКО ЯГОТИНСЬКЕ", "ХЛІБ БОРОДИНСЬКИЙ");

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(ocrResponse.getBody().rawOcrArtifact()).isNotNull();
        assertThat(ocrResponse.getBody().rawOcrArtifact().rawText()).isEqualTo(processedReceipt.getRawOcrText());
        assertThat(ocrResponse.getBody().rawOcrArtifact().lines()).isNotEmpty();
        assertThat(ocrResponse.getBody().rawOcrArtifact().lines().getFirst().confidence()).isEqualTo(0.98d);
        assertThat(ocrResponse.getBody().reconstructedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().normalizedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().receiptCountryHint()).isNull();
        assertThat(ocrResponse.getBody().languageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.AUTO_DETECTED);
        assertThat(ocrResponse.getBody().ocrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(ocrResponse.getBody().ocrProfileUsed()).isEqualTo("cyrillic");
        assertThat(ocrResponse.getBody().normalizedLines().stream().anyMatch(line -> line.tags().contains("price_like"))).isTrue();
        assertThat(ocrResponse.getBody().lineItems()).hasSizeGreaterThan(1);
        assertThat(ocrResponse.getBody().lineItems().get(0).title()).isEqualTo("МОЛОКО ЯГОТИНСЬКЕ");
        assertThat(ocrResponse.getBody().lineItems().get(0).lineTotal()).isEqualByComparingTo("85.00");
        assertThat(ocrResponse.getBody().lineItems().get(1).title()).isEqualTo("ХЛІБ БОРОДИНСЬКИЙ");
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("212.31");
        assertThat(ocrResponse.getBody().parsedCurrency()).isEqualTo(processedReceipt.getParsedCurrency());
        assertThat(ocrResponse.getBody().parseWarnings()).isEmpty();
        assertThat(ocrResponse.getBody().weakParseQuality()).isFalse();
        assertThat(ocrResponse.getBody().ocrConfidence()).isNotNull();
        assertThat(ocrResponse.getBody().ocrConfidence().overallReceiptConfidence()).isGreaterThan(0.80d);
        assertThat(ocrResponse.getBody().ocrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.PARSED_OK);
        assertThat(ocrResponse.getBody().reviewStatus()).isEqualTo(ReceiptReviewStatus.UNREVIEWED);
    }

    @Test
    void realOcrFlowUsesJavaNormalizedStreamAsParserInput() {
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(
            new OcrExtractionResult(
                "FRESH.MARKET\n1234567890123456\nTOTAL.. 210.40,\nTHANK.YOU",
                List.of(
                    new OcrExtractionLine("FRESH.MARKET", 0.99d, 0, null),
                    new OcrExtractionLine("1234567890123456", 0.98d, 1, null),
                    new OcrExtractionLine("TOTAL.. 210.40,", 0.98d, 2, null),
                    new OcrExtractionLine("THANK.YOU", 0.97d, 3, null)
                )
            )
        );

        String accessToken = registerAndLogin(uniqueEmail("ocr-normalized"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("normalized.png", MediaType.IMAGE_PNG, "png".getBytes(StandardCharsets.UTF_8), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getRawOcrArtifactJson()).isNotBlank();
        assertThat(processedReceipt.getReconstructedOcrLinesJson()).isNotBlank();
        assertThat(processedReceipt.getNormalizedOcrLinesJson()).contains("FRESH MARKET");
        assertThat(processedReceipt.getParserReadyText()).contains("FRESH MARKET");
        assertThat(processedReceipt.getLanguageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.DEFAULT_FALLBACK);
        assertThat(processedReceipt.getOcrProfileStrategy()).isEqualTo("en");
        assertThat(processedReceipt.getOcrProfileUsed()).isEqualTo("en");
        assertThat(processedReceipt.getParseWarningsJson()).isEqualTo("[]");
        assertThat(processedReceipt.isWeakParseQuality()).isFalse();
        assertThat(processedReceipt.getOcrConfidenceJson()).isNotBlank();
        assertThat(processedReceipt.getOcrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(processedReceipt.getReviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);
        assertThat(processedReceipt.getParsedStoreName()).isEqualTo("FRESH MARKET");
        assertThat(processedReceipt.getParsedTotalAmount()).isEqualByComparingTo("210.40");

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().rawOcrArtifact()).isNotNull();
        assertThat(ocrResponse.getBody().rawOcrArtifact().rawText()).contains("FRESH.MARKET");
        assertThat(ocrResponse.getBody().rawOcrArtifact().engineName()).isNull();
        assertThat(ocrResponse.getBody().rawOcrArtifact().lines()).hasSize(4);
        assertThat(ocrResponse.getBody().reconstructedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().reconstructedLines().getFirst().geometry()).isNotNull();
        assertThat(ocrResponse.getBody().reconstructedLines().getFirst().documentZone()).isEqualTo(OcrDocumentZoneType.MERCHANT_BLOCK);
        assertThat(ocrResponse.getBody().reconstructedLines().getFirst().documentZoneReasons()).contains("top_position");
        assertThat(ocrResponse.getBody().reconstructedLines().getFirst().reconstructionActions()).contains("geometry_inferred");
        assertThat(ocrResponse.getBody().normalizedLines()).extracting(line -> line.normalizedText())
            .contains("FRESH MARKET", "TOTAL. 210.40", "THANK YOU");
        assertThat(ocrResponse.getBody().normalizedLines().getFirst().tags()).contains("zone_merchant_block");
        assertThat(ocrResponse.getBody().languageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.DEFAULT_FALLBACK);
        assertThat(ocrResponse.getBody().ocrProfileStrategy()).isEqualTo("en");
        assertThat(ocrResponse.getBody().ocrProfileUsed()).isEqualTo("en");
        assertThat(ocrResponse.getBody().normalizedLines().stream().anyMatch(line -> line.ignored())).isTrue();
        assertThat(ocrResponse.getBody().parsedStoreName()).isEqualTo("FRESH MARKET");
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("210.40");
        assertThat(ocrResponse.getBody().parseWarnings()).isEmpty();
        assertThat(ocrResponse.getBody().weakParseQuality()).isFalse();
        assertThat(ocrResponse.getBody().ocrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(ocrResponse.getBody().reviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);
    }

    @Test
    void noisyReceiptPersistsValidationWarningsAndReturnsThemViaApi() throws Exception {
        String rawText = new ClassPathResource("fixtures/ocr/real-receipt-4-lines.txt").getContentAsString(StandardCharsets.UTF_8);
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(mapResult(rawText));
        String accessToken = registerAndLogin(uniqueEmail("ocr-warn"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt4.png", MediaType.IMAGE_PNG, "png".getBytes(StandardCharsets.UTF_8), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getParseWarningsJson()).contains("SUSPICIOUS_TOTAL");
        assertThat(processedReceipt.isWeakParseQuality()).isTrue();
        assertThat(processedReceipt.getOcrConfidenceJson()).isNotBlank();
        assertThat(processedReceipt.getOcrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(processedReceipt.getReviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().parseWarnings())
            .contains("SUSPICIOUS_TOTAL", "SUSPICIOUS_LINE_ITEMS");
        assertThat(ocrResponse.getBody().weakParseQuality()).isTrue();
        assertThat(ocrResponse.getBody().ocrProcessingDecision()).isEqualTo(ReceiptProcessingDecision.NEEDS_REVIEW);
        assertThat(ocrResponse.getBody().reviewStatus()).isEqualTo(ReceiptReviewStatus.NEEDS_REVIEW);
    }

    @Test
    void correctionEndpointPersistsDiffWithoutOverwritingParsedFields() {
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(
            mapResult("FRESH MARKET\nDATE 2026-04-10\nTOTAL 210.40")
        );
        String accessToken = registerAndLogin(uniqueEmail("ocr-correction"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("correction.png", MediaType.IMAGE_PNG, "png".getBytes(StandardCharsets.UTF_8), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getParsedTotalAmount()).isEqualByComparingTo("210.40");

        ReceiptCorrectionRequest correctionRequest = new ReceiptCorrectionRequest(
            "FRESH MARKET",
            java.time.LocalDate.of(2026, 4, 10),
            new java.math.BigDecimal("211.40"),
            CurrencyCode.UAH,
            null,
            false
        );

        ResponseEntity<ReceiptCorrectionResponse> correctionResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/correction",
            HttpMethod.POST,
            authorizedJsonEntity(accessToken, correctionRequest),
            ReceiptCorrectionResponse.class
        );

        assertThat(correctionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(correctionResponse.getBody()).isNotNull();
        assertThat(correctionResponse.getBody().reviewStatus()).isEqualTo(ReceiptReviewStatus.CORRECTED);
        assertThat(correctionResponse.getBody().diffs()).extracting(diff -> diff.field()).contains("totalAmount");

        Receipt correctedReceipt = receiptRepository.findDetailedById(processedReceipt.getId()).orElseThrow();
        assertThat(correctedReceipt.getParsedTotalAmount()).isEqualByComparingTo("210.40");
        assertThat(correctedReceipt.getReviewStatus()).isEqualTo(ReceiptReviewStatus.CORRECTED);

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("210.40");
        assertThat(ocrResponse.getBody().latestCorrection()).isNotNull();
        assertThat(ocrResponse.getBody().latestCorrection().correctedSnapshot().totalAmount()).isEqualByComparingTo("211.40");
    }

    @Test
    void persistenceStoresManualCountryRoutingMetadata() {
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(
            mapResult("NOVUS\nTOTAL 103.98\nDATE 2026-04-12")
        );
        String accessToken = registerAndLogin(uniqueEmail("ocr-country"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity(
                "receipt-country.png",
                MediaType.IMAGE_PNG,
                "png".getBytes(StandardCharsets.UTF_8),
                CurrencyCode.UAH,
                accessToken,
                ReceiptCountryHint.UKRAINE
            ),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getReceiptCountryHint()).isEqualTo(ReceiptCountryHint.UKRAINE);
        assertThat(processedReceipt.getLanguageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.USER_SELECTED);
        assertThat(processedReceipt.getOcrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(processedReceipt.getOcrProfileUsed()).isIn("en", "cyrillic");
    }

    @Test
    void bankLikeSummaryAmountIsPersistedAndReturnedViaApi() throws Exception {
        String rawText = new ClassPathResource("fixtures/ocr/real-receipt-6-lines.txt").getContentAsString(StandardCharsets.UTF_8);
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(mapResult(rawText));
        String accessToken = registerAndLogin(uniqueEmail("ocr-bank"), "P@ssword123");

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt6.pdf", MediaType.APPLICATION_PDF, "pdf".getBytes(StandardCharsets.UTF_8), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getParsedStoreName()).isEqualTo("UkrsibBank");
        assertThat(processedReceipt.getParsedTotalAmount()).isEqualByComparingTo("5480.00");
        assertThat(processedReceipt.getParsedPurchaseDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 2));
        assertThat(processedReceipt.getLineItems()).isEmpty();

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().parsedStoreName()).isEqualTo("UkrsibBank");
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("5480.00");
        assertThat(ocrResponse.getBody().parseWarnings()).doesNotContain("SUSPICIOUS_TOTAL");
    }

    private Receipt awaitReceiptStatus(Long receiptId, ReceiptOcrStatus expectedStatus) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(
                receiptRepository.findById(receiptId).orElseThrow().getOcrStatus()
            ).isEqualTo(expectedStatus));

        return receiptRepository.findDetailedById(receiptId).orElseThrow();
    }

    private String registerAndLogin(String email, String password) {
        restTemplate.postForEntity("/api/auth/register", new RegisterRequest(email, password), String.class);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest(email, password),
            AuthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().accessToken();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(
        String filename,
        MediaType mediaType,
        byte[] content,
        CurrencyCode currency,
        String accessToken
    ) {
        return multipartEntity(filename, mediaType, content, currency, accessToken, null);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(
        String filename,
        MediaType mediaType,
        byte[] content,
        CurrencyCode currency,
        String accessToken,
        ReceiptCountryHint receiptCountryHint
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType);
        HttpEntity<ByteArrayResource> fileEntity = new HttpEntity<>(
            new NamedByteArrayResource(filename, content),
            fileHeaders
        );

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileEntity);
        body.add("currency", currency.name());
        if (receiptCountryHint != null) {
            body.add("receiptCountryHint", receiptCountryHint.name());
        }

        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authorizedEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Object> authorizedJsonEntity(String accessToken, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(body, headers);
    }

    private void clearBucket() {
        s3Client.listObjectsV2Paginator(
                ListObjectsV2Request.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .build()
            )
            .contents()
            .forEach(s3Object -> s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(s3Object.key())
                    .build()
            ));
    }

    private void drainOcrQueue() {
        while (true) {
            List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(resolveOcrQueueUrl())
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build()
            ).messages();

            if (messages.isEmpty()) {
                return;
            }

            messages.forEach(message -> sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                    .queueUrl(resolveOcrQueueUrl())
                    .receiptHandle(message.receiptHandle())
                    .build()
            ));
        }
    }

    private String resolveOcrQueueUrl() {
        return sqsClient.getQueueUrl(
            GetQueueUrlRequest.builder()
                .queueName(awsProperties.getSqs().getReceiptOcrQueueName())
                .build()
        ).queueUrl();
    }

    private void ensureOcrQueueExists() {
        sqsClient.createQueue(
            CreateQueueRequest.builder()
                .queueName(awsProperties.getSqs().getReceiptOcrQueueName())
                .build()
        );
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private OcrExtractionResult mapResult(String rawText) {
        List<OcrExtractionLine> lines = rawText.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .map(line -> new OcrExtractionLine(line, 0.98d, null, null))
            .toList();
        return new OcrExtractionResult(rawText, lines);
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(String filename, byte[] byteArray) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
