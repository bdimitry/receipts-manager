package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.ReportGenerationMessage;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ErrorResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportJobQueuePublishingIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReportJobRepository reportJobRepository;

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
        reportJobRepository.deleteAll();
        userRepository.deleteAll();
        drainQueue();
    }

    @Test
    void createMonthlyCompatibilityEndpointStoresCsvJobAndPublishesMessage() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        ResponseEntity<ReportJobResponse> response = restTemplate.exchange(
            "/api/reports/monthly",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateMonthlyReportRequest(2026, 3), accessToken),
            ReportJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().reportType()).isEqualTo(ReportType.MONTHLY_SPENDING);
        assertThat(response.getBody().reportFormat()).isEqualTo(ReportFormat.CSV);
        assertThat(response.getBody().status()).isEqualTo(ReportJobStatus.NEW);

        ReportJob savedJob = reportJobRepository.findById(response.getBody().id()).orElseThrow();
        Message message = receiveSingleMessage();
        ReportGenerationMessage queueMessage = objectMapper.readValue(message.body(), ReportGenerationMessage.class);

        assertThat(queueMessage.reportJobId()).isEqualTo(savedJob.getId());
        assertThat(queueMessage.userId()).isEqualTo(savedJob.getUser().getId());
        assertThat(queueMessage.year()).isEqualTo(2026);
        assertThat(queueMessage.month()).isEqualTo(3);
        assertThat(queueMessage.reportType()).isEqualTo(ReportType.MONTHLY_SPENDING);
        assertThat(queueMessage.reportFormat()).isEqualTo(ReportFormat.CSV);
    }

    @Test
    void createGenericReportStoresRequestedTypeAndFormatAndPublishesMessage() throws Exception {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        ResponseEntity<ReportJobResponse> response = createReportJob(
            accessToken,
            2026,
            4,
            ReportType.CATEGORY_SUMMARY,
            ReportFormat.PDF
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().reportType()).isEqualTo(ReportType.CATEGORY_SUMMARY);
        assertThat(response.getBody().reportFormat()).isEqualTo(ReportFormat.PDF);
        assertThat(response.getBody().status()).isEqualTo(ReportJobStatus.NEW);

        Message message = receiveSingleMessage();
        ReportGenerationMessage queueMessage = objectMapper.readValue(message.body(), ReportGenerationMessage.class);

        assertThat(queueMessage.reportType()).isEqualTo(ReportType.CATEGORY_SUMMARY);
        assertThat(queueMessage.reportFormat()).isEqualTo(ReportFormat.PDF);
    }

    @Test
    void createReportJobWithoutTokenReturnsUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/reports",
            new CreateReportRequest(2026, 3, ReportType.MONTHLY_SPENDING, ReportFormat.CSV),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createReportJobWithUnsupportedTypeReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/api/reports",
            HttpMethod.POST,
            authorizedJsonStringEntity(
                """
                {"year":2026,"month":3,"reportType":"UNSUPPORTED","reportFormat":"CSV"}
                """,
                accessToken
            ),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("unsupported enum");
    }

    @Test
    void createReportJobWithUnsupportedFormatReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/api/reports",
            HttpMethod.POST,
            authorizedJsonStringEntity(
                """
                {"year":2026,"month":3,"reportType":"MONTHLY_SPENDING","reportFormat":"DOCX"}
                """,
                accessToken
            ),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("unsupported enum");
    }

    @Test
    void listReportJobsReturnsOnlyCurrentUsersData() {
        String firstUserToken = registerAndLogin(uniqueEmail("first"), "P@ssword123");
        createReportJob(firstUserToken, 2026, 3, ReportType.MONTHLY_SPENDING, ReportFormat.CSV);

        String secondUserToken = registerAndLogin(uniqueEmail("second"), "P@ssword123");
        createReportJob(secondUserToken, 2026, 4, ReportType.STORE_SUMMARY, ReportFormat.XLSX);

        ResponseEntity<List<ReportJobResponse>> response = restTemplate.exchange(
            "/api/reports",
            HttpMethod.GET,
            authorizedEntity(firstUserToken),
            new ParameterizedTypeReference<>() { }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().getFirst().reportType()).isEqualTo(ReportType.MONTHLY_SPENDING);
        assertThat(response.getBody().getFirst().reportFormat()).isEqualTo(ReportFormat.CSV);
    }

    @Test
    void getOwnReportJobReturnsOk() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob =
            createReportJob(accessToken, 2026, 5, ReportType.STORE_SUMMARY, ReportFormat.XLSX).getBody();

        ResponseEntity<ReportJobResponse> response = restTemplate.exchange(
            "/api/reports/" + createdJob.id(),
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReportJobResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(createdJob.id());
        assertThat(response.getBody().reportType()).isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(response.getBody().reportFormat()).isEqualTo(ReportFormat.XLSX);
    }

    @Test
    void getAnotherUsersReportJobReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob =
            createReportJob(ownerToken, 2026, 6, ReportType.CATEGORY_SUMMARY, ReportFormat.PDF).getBody();
        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/reports/" + createdJob.id(),
            HttpMethod.GET,
            authorizedEntity(anotherUserToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadPendingReportReturnsConflict() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob =
            createReportJob(accessToken, 2026, 9, ReportType.MONTHLY_SPENDING, ReportFormat.PDF).getBody();

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/api/reports/" + createdJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("not ready");
    }

    @Test
    void downloadAnotherUsersReportReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob =
            createReportJob(ownerToken, 2026, 10, ReportType.MONTHLY_SPENDING, ReportFormat.XLSX).getBody();
        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/api/reports/" + createdJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(anotherUserToken),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<ReportJobResponse> createReportJob(
        String accessToken,
        int year,
        int month,
        ReportType reportType,
        ReportFormat reportFormat
    ) {
        return restTemplate.exchange(
            "/api/reports",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateReportRequest(year, month, reportType, reportFormat), accessToken),
            ReportJobResponse.class
        );
    }

    private Message receiveSingleMessage() {
        String queueUrl = getQueueUrl();
        AtomicReference<Message> messageReference = new AtomicReference<>();

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                List<Message> messages = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(1)
                        .build()
                ).messages();
                assertThat(messages).hasSize(1);
                messageReference.set(messages.getFirst());
            });

        Message message = messageReference.get();
        sqsClient.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build()
        );
        return message;
    }

    private void drainQueue() {
        String queueUrl = getQueueUrl();

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

    private String getQueueUrl() {
        return sqsClient.getQueueUrl(
            GetQueueUrlRequest.builder()
                .queueName(awsProperties.getSqs().getQueueName())
                .build()
        ).queueUrl();
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

    private HttpEntity<String> authorizedJsonStringEntity(String body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
