package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.ReceiptOcrMessage;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
        "app.receipts.ocr.consumer.enabled=false",
        "aws.sqs.receipt-ocr-queue-name=receipt-ocr-queue-publishing-test"
    }
)
class ReceiptOcrQueuePublishingIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private AwsProperties awsProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ensureOcrQueueExists();
        receiptRepository.deleteAll();
        userRepository.deleteAll();
        drainOcrQueue();
    }

    @Test
    void uploadReceiptPublishesReceiptOcrMessageAndKeepsNewStatus() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("ocr"), "P@ssword123");

        ResponseEntity<ReceiptResponse> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.pdf", MediaType.APPLICATION_PDF, "demo-pdf".getBytes(), CurrencyCode.UAH, accessToken),
            ReceiptResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(response.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.NEW);

        Message message = receiveSingleMessage();
        ReceiptOcrMessage queueMessage = objectMapper.readValue(message.body(), ReceiptOcrMessage.class);
        assertThat(queueMessage.receiptId()).isEqualTo(response.getBody().id());
        assertThat(queueMessage.userId()).isNotNull();
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

    private void drainOcrQueue() {
        while (true) {
            List<Message> messages = readMessages();

            if (messages.isEmpty()) {
                return;
            }

            messages.forEach(this::deleteMessage);
        }
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(resolveOcrQueueUrl())
                .receiptHandle(message.receiptHandle())
                .build()
        );
    }

    private List<Message> readMessages() {
        return sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(resolveOcrQueueUrl())
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .build()
        ).messages();
    }

    private Message receiveSingleMessage() {
        AtomicReference<Message> messageReference = new AtomicReference<>();

        Awaitility.await().untilAsserted(() -> {
            List<Message> messages = readMessages();
            assertThat(messages).hasSize(1);
            messageReference.set(messages.getFirst());
        });

        Message message = messageReference.get();
        deleteMessage(message);
        return message;
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
