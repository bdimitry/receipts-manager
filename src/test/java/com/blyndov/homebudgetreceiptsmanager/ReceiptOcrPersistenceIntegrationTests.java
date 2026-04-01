package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
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
    private PurchaseRepository purchaseRepository;

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
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
        clearBucket();
        drainOcrQueue();
        when(ocrClient.extractText(any(), any(), any())).thenReturn(
            new ClassPathResource("fixtures/ocr/receipt-cyrillic-noisy.txt").getContentAsString(StandardCharsets.UTF_8)
        );
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
        assertThat(processedReceipt.getLineItems()).hasSizeGreaterThan(1);
        assertThat(processedReceipt.getLineItems())
            .extracting(item -> item.getTitle())
            .contains("МОЛОКО ЯГОТИНСЬКЕ", "ХЛІБ БОРОДИНСЬКИЙ", "Пакет");

        ResponseEntity<ReceiptOcrResponse> ocrResponse = restTemplate.exchange(
            "/api/receipts/" + processedReceipt.getId() + "/ocr",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptOcrResponse.class
        );

        assertThat(ocrResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResponse.getBody()).isNotNull();
        assertThat(ocrResponse.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(ocrResponse.getBody().lineItems()).hasSizeGreaterThan(1);
        assertThat(ocrResponse.getBody().lineItems().get(0).title()).isEqualTo("МОЛОКО ЯГОТИНСЬКЕ");
        assertThat(ocrResponse.getBody().lineItems().get(0).lineTotal()).isEqualByComparingTo("85.00");
        assertThat(ocrResponse.getBody().lineItems().get(1).title()).isEqualTo("ХЛІБ БОРОДИНСЬКИЙ");
        assertThat(ocrResponse.getBody().parsedTotalAmount()).isEqualByComparingTo("212.31");
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
