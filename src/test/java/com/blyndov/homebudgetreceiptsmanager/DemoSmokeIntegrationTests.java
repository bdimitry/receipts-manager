package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CurrentUserResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportDownloadResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.core.ParameterizedTypeReference;
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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=true",
        "app.report-jobs.consumer.poll-delay-ms=100",
        "app.report-jobs.consumer.wait-time-seconds=1",
        "app.receipts.ocr.consumer.enabled=true",
        "app.receipts.ocr.consumer.poll-delay-ms=100",
        "app.receipts.ocr.consumer.wait-time-seconds=1"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoSmokeIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private AwsProperties awsProperties;

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        receiptRepository.deleteAll();
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
        purgeEmails();
        purgeTelegramMessages();
        clearBucket();
        drainQueue(awsProperties.getSqs().getQueueName());
        drainQueue(awsProperties.getSqs().getReceiptOcrQueueName());
    }

    @Test
    void demoReadySmokeScenarioCoversMainBackendFlow() throws Exception {
        String email = uniqueEmail("demo");
        String password = "P@ssword123";

        ResponseEntity<CurrentUserResponse> registerResponse = restTemplate.postForEntity(
            "/api/auth/register",
            new RegisterRequest(email, password),
            CurrentUserResponse.class
        );
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest(email, password),
            AuthResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        String accessToken = loginResponse.getBody().accessToken();

        ResponseEntity<CurrentUserResponse> meResponse = restTemplate.exchange(
            "/api/users/me",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            CurrentUserResponse.class
        );
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).isNotNull();
        assertThat(meResponse.getBody().email()).isEqualTo(email);

        ResponseEntity<PurchaseResponse> purchaseResponse = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    "Groceries",
                    "FOOD",
                    new BigDecimal("42.75"),
                    CurrencyCode.UAH,
                    LocalDate.of(2026, 3, 30),
                    "Fresh Market",
                    "Demo flow purchase",
                    null
                ),
                accessToken
            ),
            PurchaseResponse.class
        );
        assertThat(purchaseResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(purchaseResponse.getBody()).isNotNull();
        assertThat(purchaseResponse.getBody().items()).isEmpty();

        ResponseEntity<ReceiptResponse> receiptResponse = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity(
                "demo-receipt.png",
                MediaType.IMAGE_PNG,
                createReceiptImage(List.of("FRESH MARKET", "TOTAL 42.75", "DATE 2026-03-30")),
                purchaseResponse.getBody().id(),
                CurrencyCode.UAH,
                accessToken
            ),
            ReceiptResponse.class
        );
        assertThat(receiptResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(receiptResponse.getBody()).isNotNull();
        assertThat(receiptResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(
            s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(receiptResponse.getBody().s3Key())
                    .build()
            ).contentLength()
        ).isEqualTo(receiptResponse.getBody().fileSize());
        assertThat(receiptResponse.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.NEW);

        ReceiptOcrResponse completedOcr = awaitOcrDone(receiptResponse.getBody().id(), accessToken);
        assertThat(completedOcr.rawOcrText()).containsIgnoringCase("FRESH");
        assertThat(completedOcr.currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(completedOcr.parsedStoreName()).isEqualTo("FRESH MARKET");
        assertThat(completedOcr.parsedTotalAmount()).isEqualByComparingTo("42.75");

        ResponseEntity<ReportJobResponse> createReportResponse = restTemplate.exchange(
            "/api/reports/monthly",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateMonthlyReportRequest(2026, 3), accessToken),
            ReportJobResponse.class
        );
        assertThat(createReportResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createReportResponse.getBody()).isNotNull();
        assertThat(createReportResponse.getBody().status()).isEqualTo(ReportJobStatus.NEW);

        ReportJobResponse completedJob = awaitDone(createReportResponse.getBody().id(), accessToken);
        assertThat(completedJob.s3Key()).isNotBlank();

        ResponseBytes<GetObjectResponse> reportBytes = s3Client.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(awsProperties.getS3().getBucketName())
                .key(completedJob.s3Key())
                .build()
        );
        String reportCsv = reportBytes.asString(StandardCharsets.UTF_8);
        assertThat(reportCsv).contains("Monthly Spending Report");
        assertThat(reportCsv).contains("Groceries");
        assertThat(reportCsv).contains("42.75");

        ResponseEntity<ReportDownloadResponse> downloadResponse = restTemplate.exchange(
            "/api/reports/" + completedJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReportDownloadResponse.class
        );
        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isNotNull();
        assertThat(downloadResponse.getBody().downloadUrl()).contains(completedJob.s3Key());

        ResponseEntity<List<ReceiptResponse>> receiptsResponse = restTemplate.exchange(
            "/api/receipts",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            new ParameterizedTypeReference<>() { }
        );
        assertThat(receiptsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receiptsResponse.getBody()).isNotNull();
        assertThat(receiptsResponse.getBody()).hasSize(1);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(receivedEmails()).hasSize(1));

        MimeMessage notification = receivedEmails()[0];
        assertThat(notification.getAllRecipients()).hasSize(1);
        assertThat(notification.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(notification.getContent().toString()).contains("ready");
        assertThat(notification.getContent().toString()).contains("/api/reports/" + completedJob.id() + "/download");
        assertThat(receivedTelegramMessages()).isEmpty();
    }

    private ReportJobResponse awaitDone(Long reportJobId, String accessToken) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> {
                ReportJobResponse response = restTemplate.exchange(
                    "/api/reports/" + reportJobId,
                    HttpMethod.GET,
                    authorizedEntity(accessToken),
                    ReportJobResponse.class
                ).getBody();
                assertThat(response).isNotNull();
                assertThat(response.status()).isEqualTo(ReportJobStatus.DONE);
                assertThat(response.s3Key()).isNotBlank();
            });

        return reportJobRepository.findById(reportJobId)
            .map(reportJob -> new ReportJobResponse(
                reportJob.getId(),
                reportJob.getYear(),
                reportJob.getMonth(),
                reportJob.getReportType(),
                reportJob.getReportFormat(),
                reportJob.getStatus(),
                reportJob.getS3Key(),
                reportJob.getErrorMessage(),
                reportJob.getCreatedAt(),
                reportJob.getUpdatedAt()
            ))
            .orElseThrow();
    }

    private ReceiptOcrResponse awaitOcrDone(Long receiptId, String accessToken) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> {
                ReceiptOcrResponse response = restTemplate.exchange(
                    "/api/receipts/" + receiptId + "/ocr",
                    HttpMethod.GET,
                    authorizedEntity(accessToken),
                    ReceiptOcrResponse.class
                ).getBody();
                assertThat(response).isNotNull();
                assertThat(response.ocrStatus()).isEqualTo(ReceiptOcrStatus.DONE);
                assertThat(response.rawOcrText()).isNotBlank();
            });

        return restTemplate.exchange(
            "/api/receipts/" + receiptId + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        ).getBody();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(
        String filename,
        MediaType mediaType,
        byte[] content,
        Long purchaseId,
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
        body.add("purchaseId", purchaseId.toString());
        body.add("currency", currency.name());

        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Void> authorizedEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    private <T> HttpEntity<T> authorizedJsonEntity(T body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
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

    private void drainQueue(String queueName) {
        String queueUrl = sqsClient.getQueueUrl(
            GetQueueUrlRequest.builder()
                .queueName(queueName)
                .build()
        ).queueUrl();

        while (true) {
            List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build()
            ).messages();

            if (messages.isEmpty()) {
                return;
            }

            messages.forEach(message -> sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build()
            ));
        }
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private byte[] createReceiptImage(List<String> lines) throws Exception {
        BufferedImage image = new BufferedImage(1400, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("Monospaced", Font.BOLD, 48));
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
