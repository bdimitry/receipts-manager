package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.OcrLanguageDetectionSource;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCountryHint;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
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
        "aws.sqs.receipt-ocr-queue-name=receipt-ocr-queue-processing-test"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiptOcrProcessingIntegrationTests extends AbstractPostgresIntegrationTest {

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

    @BeforeEach
    void setUp() {
        ensureOcrQueueExists();
        receiptRepository.deleteAll();
        userRepository.deleteAll();
        clearBucket();
        drainOcrQueue();
    }

    @Test
    void uploadReceiptTriggersRealOcrAndPersistsParsedFields() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("ocr"), "P@ssword123");
        byte[] image = createReceiptImage(
            List.of("FRESH MARKET", "TOTAL 123.45 UAH", "DATE 2026-03-14")
        );

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.png", MediaType.IMAGE_PNG, image, CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody()).isNotNull();
        assertThat(uploadResponse.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.NEW);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(
                receiptRepository.findById(uploadResponse.getBody().id()).orElseThrow().getOcrStatus()
            ).isEqualTo(ReceiptOcrStatus.PROCESSING));

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getRawOcrText()).isNotBlank();
        assertThat(processedReceipt.getRawOcrText().toUpperCase()).contains("FRESH");
        assertThat(processedReceipt.getRawOcrArtifactJson()).isNotBlank();
        assertThat(processedReceipt.getReconstructedOcrLinesJson()).isNotBlank();
        assertThat(processedReceipt.getNormalizedOcrLinesJson()).isNotBlank();
        assertThat(processedReceipt.getParserReadyText()).contains("FRESH MARKET");
        assertThat(processedReceipt.getReceiptCountryHint()).isNull();
        assertThat(processedReceipt.getLanguageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.DEFAULT_FALLBACK);
        assertThat(processedReceipt.getOcrProfileStrategy()).isEqualTo("en");
        assertThat(processedReceipt.getOcrProfileUsed()).isEqualTo("en");
        assertThat(processedReceipt.getParsedStoreName()).isEqualTo("FRESH MARKET");
        assertThat(processedReceipt.getParsedTotalAmount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(processedReceipt.getParsedCurrency()).isEqualTo(CurrencyCode.UAH);
        assertThat(processedReceipt.getParsedPurchaseDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(processedReceipt.getOcrErrorMessage()).isNull();
        assertThat(processedReceipt.getOcrProcessedAt()).isNotNull();

        ResponseEntity<ReceiptResponse> receiptResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId(),
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptResponse.class
        );

        assertThat(receiptResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receiptResponse.getBody()).isNotNull();
        assertThat(receiptResponse.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.DONE);
        assertThat(receiptResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(receiptResponse.getBody().parsedStoreName()).isEqualTo("FRESH MARKET");

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(ocrResponse.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.DONE);
        assertThat(ocrResponse.getBody().rawOcrText()).containsIgnoringCase("FRESH");
        assertThat(ocrResponse.getBody().rawOcrArtifact()).isNotNull();
        assertThat(ocrResponse.getBody().rawOcrArtifact().engineName()).isEqualTo("PaddleOCR");
        assertThat(ocrResponse.getBody().rawOcrArtifact().rawText()).containsIgnoringCase("FRESH");
        assertThat(ocrResponse.getBody().rawOcrArtifact().lines()).isNotEmpty();
        assertThat(ocrResponse.getBody().rawOcrArtifact().pages()).isNotEmpty();
        assertThat(ocrResponse.getBody().rawOcrArtifact().preprocessingSteps()).isNotNull();
        assertThat(ocrResponse.getBody().reconstructedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().normalizedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().receiptCountryHint()).isNull();
        assertThat(ocrResponse.getBody().languageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.DEFAULT_FALLBACK);
        assertThat(ocrResponse.getBody().ocrProfileStrategy()).isEqualTo("en");
        assertThat(ocrResponse.getBody().ocrProfileUsed()).isEqualTo("en");
        assertThat(ocrResponse.getBody().normalizedLines().stream().anyMatch(line -> line.tags().contains("price_like"))).isTrue();
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("123.45");
        assertThat(ocrResponse.getBody().parsedCurrency()).isEqualTo(CurrencyCode.UAH);
        assertThat(ocrResponse.getBody().parseWarnings()).isEmpty();
        assertThat(ocrResponse.getBody().weakParseQuality()).isFalse();
    }

    @Test
    void multilingualOcrHandlesCyrillicAndEnglishReceiptText() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("ocr-multilingual"), "P@ssword123");
        byte[] image = createReceiptImage(
            List.of(
                "СІЛЬПО MARKET",
                "МОЛОКО 2 x 42.50 85.00",
                "BREAD 39.90",
                "TOTAL 124.90",
                "DATE 2026-03-14"
            )
        );

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt-multilingual.png", MediaType.IMAGE_PNG, image, CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploadResponse.getBody()).isNotNull();

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getLanguageDetectionSource()).isIn(
            OcrLanguageDetectionSource.AUTO_DETECTED,
            OcrLanguageDetectionSource.DEFAULT_FALLBACK
        );
        assertThat(processedReceipt.getOcrProfileStrategy()).isIn("en+cyrillic", "en");
        assertThat(processedReceipt.getOcrProfileUsed()).isIn("en", "cyrillic");
        assertThat(processedReceipt.getRawOcrText()).containsIgnoringCase("TOTAL");
        assertThat(processedReceipt.getParsedTotalAmount()).isEqualByComparingTo(new BigDecimal("124.90"));
        assertThat(processedReceipt.getLineItems()).hasSizeGreaterThan(1);

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().languageDetectionSource()).isIn(
            OcrLanguageDetectionSource.AUTO_DETECTED,
            OcrLanguageDetectionSource.DEFAULT_FALLBACK
        );
        assertThat(ocrResponse.getBody().ocrProfileStrategy()).isIn("en+cyrillic", "en");
        assertThat(ocrResponse.getBody().ocrProfileUsed()).isIn("en", "cyrillic");
        assertThat(ocrResponse.getBody().normalizedLines()).isNotEmpty();
        assertThat(ocrResponse.getBody().lineItems()).hasSizeGreaterThan(1);
        assertThat(ocrResponse.getBody().parseWarnings()).isEmpty();
    }

    @Test
    void manualCountryHintUsesSelectedRoutingStrategy() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("ocr-country"), "P@ssword123");
        byte[] image = createReceiptImage(
            List.of("SILPO MARKET", "МОЛОКО 2 x 42.50 85.00", "TOTAL 124.90", "DATE 2026-03-14")
        );

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt-ua.png", MediaType.IMAGE_PNG, image, CurrencyCode.UAH, accessToken, ReceiptCountryHint.UKRAINE),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        assertThat(processedReceipt.getReceiptCountryHint()).isEqualTo(ReceiptCountryHint.UKRAINE);
        assertThat(processedReceipt.getLanguageDetectionSource()).isEqualTo(OcrLanguageDetectionSource.USER_SELECTED);
        assertThat(processedReceipt.getOcrProfileStrategy()).isEqualTo("en+cyrillic");
        assertThat(processedReceipt.getOcrProfileUsed()).isIn("en", "cyrillic");
    }

    @Test
    void ocrDoneAllowsPartialParsingWhenRawTextExists() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("ocr"), "P@ssword123");
        byte[] image = createReceiptImage(List.of("CORNER SHOP", "THANK YOU FOR VISITING"));

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("partial.png", MediaType.IMAGE_PNG, image, CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);

        assertThat(processedReceipt.getRawOcrText()).isNotBlank();
        assertThat(processedReceipt.getParsedStoreName()).isNotBlank();
        assertThat(processedReceipt.getParsedTotalAmount()).isNull();
        assertThat(processedReceipt.getParsedPurchaseDate()).isNull();
        assertThat(processedReceipt.isWeakParseQuality()).isFalse();
    }

    @Test
    void ocrResultIsVisibleOnlyToReceiptOwner() throws Exception {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        byte[] image = createReceiptImage(List.of("FRESH MARKET", "TOTAL 50.00", "DATE 2026-03-14"));

        ResponseEntity<ReceiptResponse> uploadResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("owner.png", MediaType.IMAGE_PNG, image, CurrencyCode.UAH, ownerToken),
            ReceiptResponse.class
        );

        Receipt processedReceipt = awaitReceiptStatus(uploadResponse.getBody().id(), ReceiptOcrStatus.DONE);
        String intruderToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(intruderToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Receipt awaitReceiptStatus(Long receiptId, ReceiptOcrStatus expectedStatus) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(180))
            .untilAsserted(() -> {
                Receipt receipt = receiptRepository.findById(receiptId).orElseThrow();
                assertThat(receipt.getOcrStatus()).isEqualTo(expectedStatus);
            });

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

    private byte[] createReceiptImage(List<String> lines) throws Exception {
        BufferedImage image = new BufferedImage(1400, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 56));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int y = 120;
            for (String line : lines) {
                graphics.drawString(line, 80, y);
                y += 100;
            }
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
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
