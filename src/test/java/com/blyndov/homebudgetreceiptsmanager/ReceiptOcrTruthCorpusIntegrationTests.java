package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionLine;
import com.blyndov.homebudgetreceiptsmanager.client.OcrExtractionResult;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptLineItemResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptProcessingDecision;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptReviewStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
        "aws.sqs.receipt-ocr-queue-name=receipt-ocr-queue-truth-corpus-test"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiptOcrTruthCorpusIntegrationTests extends AbstractPostgresIntegrationTest {

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
    void setUp() {
        ensureOcrQueueExists();
        receiptRepository.deleteAll();
        userRepository.deleteAll();
        clearBucket();
        drainOcrQueue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("truthCorpus")
    void receiptOcrFlowStaysCloseToHumanTruth(String sample, TruthCase truthCase) throws Exception {
        String rawText = new ClassPathResource(truthCase.ocrFixture()).getContentAsString(StandardCharsets.UTF_8);
        when(ocrClient.extractResult(any(), any(), any(), any())).thenReturn(mapResult(rawText));
        String accessToken = registerAndLogin(uniqueEmail("ocr-truth"), "P@ssword123");
        byte[] receiptBytes = new ClassPathResource("reciepts/" + truthCase.uploadFilename())
            .getContentAsByteArray();

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity(
                truthCase.uploadFilename(),
                MediaType.parseMediaType(truthCase.contentType()),
                receiptBytes,
                CurrencyCode.UAH,
                accessToken
            ),
            ReceiptResponse.class
        );

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody()).isNotNull();

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResponse.getBody()).isNotNull();
        TruthScore score = scoreAgainstTruth(truthCase, ocrResponse.getBody());
        System.out.printf(
            Locale.ROOT,
            "TRUTH_SCORE sample=%s gate=%s score=%.2f minimum=%.2f misses=%s%n",
            sample,
            truthCase.gateMode(),
            score.value(),
            truthCase.minimumScore(),
            score.misses()
        );

        if (truthCase.gateMode() == TruthGateMode.STRICT) {
            assertThat(score.value())
                .describedAs(
                    "%s truth score %.2f must be >= %.2f. Misses: %s. Actual store=%s total=%s currency=%s date=%s items=%s decision=%s review=%s",
                    sample,
                    score.value(),
                    truthCase.minimumScore(),
                    score.misses(),
                    ocrResponse.getBody().parsedStoreName(),
                    ocrResponse.getBody().parsedTotalAmount(),
                    ocrResponse.getBody().parsedCurrency(),
                    ocrResponse.getBody().parsedPurchaseDate(),
                    ocrResponse.getBody().lineItems() == null ? 0 : ocrResponse.getBody().lineItems().size(),
                    ocrResponse.getBody().ocrProcessingDecision(),
                    ocrResponse.getBody().reviewStatus()
                )
                .isGreaterThanOrEqualTo(truthCase.minimumScore());
        } else {
            assertThat(score.value())
                .describedAs("%s report-only baseline must still produce a score", sample)
                .isBetween(0d, 1d);
        }
    }

    private static Stream<Arguments> truthCorpus() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<TruthCase> cases = mapper.readValue(
            new ClassPathResource("fixtures/ocr/truth-corpus/expected-receipt-results.json").getInputStream(),
            new TypeReference<>() {
            }
        );
        return cases.stream().map(testCase -> Arguments.of(testCase.sample(), testCase));
    }

    private TruthScore scoreAgainstTruth(TruthCase truthCase, ReceiptOcrResponse actual) {
        int earned = 0;
        int max = 0;
        List<String> misses = new ArrayList<>();

        if (!truthCase.expectedStoreAliases().isEmpty()) {
            max += 20;
            if (matchesAnyAlias(actual.parsedStoreName(), truthCase.expectedStoreAliases())) {
                earned += 20;
            } else {
                misses.add("store expected one of " + truthCase.expectedStoreAliases());
            }
        }

        if (truthCase.expectedTotal() != null) {
            max += 25;
            if (actual.parsedTotalAmount() != null
                && actual.parsedTotalAmount().compareTo(truthCase.expectedTotal()) == 0) {
                earned += 25;
            } else {
                misses.add("total expected " + truthCase.expectedTotal());
            }
        }

        if (truthCase.expectedCurrency() != null) {
            max += 10;
            if (truthCase.expectedCurrency() == actual.parsedCurrency()) {
                earned += 10;
            } else {
                misses.add("currency expected " + truthCase.expectedCurrency());
            }
        }

        if (truthCase.expectedPurchaseDate() != null) {
            max += 20;
            if (truthCase.expectedPurchaseDate().equals(actual.parsedPurchaseDate())) {
                earned += 20;
            } else {
                misses.add("date expected " + truthCase.expectedPurchaseDate());
            }
        }

        List<ReceiptLineItemResponse> actualItems = actual.lineItems() == null ? List.of() : actual.lineItems();
        if (truthCase.expectedItemCount() != null) {
            max += 10;
            if (actualItems.size() == truthCase.expectedItemCount()) {
                earned += 10;
            } else {
                misses.add("item count expected " + truthCase.expectedItemCount() + " but was " + actualItems.size());
            }
        }

        if (!truthCase.expectedItemTitleFragments().isEmpty()) {
            max += 15;
            int matchedFragments = 0;
            for (String fragment : truthCase.expectedItemTitleFragments()) {
                if (actualItems.stream().anyMatch(item -> lineItemContains(item, fragment))) {
                    matchedFragments++;
                } else {
                    misses.add("item fragment missing " + fragment);
                }
            }
            earned += BigDecimal.valueOf(15L * matchedFragments)
                .divide(BigDecimal.valueOf(truthCase.expectedItemTitleFragments().size()), 0, RoundingMode.HALF_UP)
                .intValue();
        }

        if (truthCase.expectedProcessingDecision() != null) {
            max += 5;
            if (truthCase.expectedProcessingDecision() == actual.ocrProcessingDecision()) {
                earned += 5;
            } else {
                misses.add("decision expected " + truthCase.expectedProcessingDecision());
            }
        }

        if (truthCase.expectedReviewStatus() != null) {
            max += 5;
            if (truthCase.expectedReviewStatus() == actual.reviewStatus()) {
                earned += 5;
            } else {
                misses.add("review expected " + truthCase.expectedReviewStatus());
            }
        }

        double value = max == 0 ? 1.0d : BigDecimal.valueOf(earned)
            .divide(BigDecimal.valueOf(max), 4, RoundingMode.HALF_UP)
            .doubleValue();
        return new TruthScore(value, misses);
    }

    private boolean matchesAnyAlias(String actual, List<String> aliases) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        String normalizedActual = normalizeForTruth(actual);
        return aliases.stream()
            .map(this::normalizeForTruth)
            .anyMatch(alias -> normalizedActual.contains(alias) || alias.contains(normalizedActual));
    }

    private boolean lineItemContains(ReceiptLineItemResponse item, String fragment) {
        String haystack = normalizeForTruth((item.title() == null ? "" : item.title()) + " " + (item.rawFragment() == null ? "" : item.rawFragment()));
        return haystack.contains(normalizeForTruth(fragment));
    }

    private String normalizeForTruth(String value) {
        return value == null ? "" : value
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
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

        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authorizedEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
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

    private record TruthCase(
        String sample,
        String ocrFixture,
        String uploadFilename,
        String contentType,
        List<String> expectedStoreAliases,
        BigDecimal expectedTotal,
        CurrencyCode expectedCurrency,
        LocalDate expectedPurchaseDate,
        Integer expectedItemCount,
        List<String> expectedItemTitleFragments,
        ReceiptProcessingDecision expectedProcessingDecision,
        ReceiptReviewStatus expectedReviewStatus,
        TruthGateMode gateMode,
        double minimumScore
    ) {
    }

    private enum TruthGateMode {
        STRICT,
        BASELINE
    }

    private record TruthScore(double value, List<String> misses) {
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
