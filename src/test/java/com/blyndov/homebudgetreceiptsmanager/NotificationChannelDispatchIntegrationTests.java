package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.UpdateNotificationSettingsRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
        "app.report-jobs.consumer.enabled=true",
        "app.report-jobs.consumer.poll-delay-ms=100",
        "app.report-jobs.consumer.wait-time-seconds=1",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationChannelDispatchIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private AwsProperties awsProperties;

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
        purgeEmails();
        purgeTelegramMessages();
        drainQueue();
    }

    @Test
    void preferredEmailChannelSendsOnlyEmail() throws Exception {
        String email = uniqueEmail("email");
        String accessToken = registerAndLogin(email, "P@ssword123");
        createPurchase(accessToken);

        ReportJobResponse createdJob = createReport(accessToken, ReportType.MONTHLY_SPENDING, ReportFormat.CSV).getBody();
        ReportJobResponse completedJob = awaitDone(createdJob.id(), accessToken);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedEmails()).hasSize(1));
        MimeMessage notification = receivedEmails()[0];

        assertThat(notification.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(notification.getSubject()).contains("ready");
        assertThat(notification.getContent().toString()).contains("CSV");
        assertThat(receivedTelegramMessages()).isEmpty();
        assertThat(completedJob.status()).isEqualTo(ReportJobStatus.DONE);
    }

    @Test
    void preferredTelegramChannelSendsOnlyTelegram() {
        String accessToken = registerAndLogin(uniqueEmail("telegram"), "P@ssword123");
        updateNotificationSettings(accessToken, NotificationChannel.TELEGRAM, "555000111");
        createPurchase(accessToken);

        ReportJobResponse createdJob = createReport(accessToken, ReportType.MONTHLY_SPENDING, ReportFormat.PDF).getBody();
        ReportJobResponse completedJob = awaitDone(createdJob.id(), accessToken);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedTelegramMessages()).hasSize(1));
        AbstractPostgresIntegrationTest.TelegramMockMessage message = receivedTelegramMessages().getFirst();

        assertThat(message.chat_id()).isEqualTo("555000111");
        assertThat(message.text()).contains("Your report is ready");
        assertThat(message.text()).contains("PDF");
        assertThat(message.text()).contains("/api/reports/" + completedJob.id() + "/download");
        assertThat(receivedEmails()).isEmpty();
        assertThat(completedJob.status()).isEqualTo(ReportJobStatus.DONE);
    }

    private void updateNotificationSettings(
        String accessToken,
        NotificationChannel channel,
        String telegramChatId
    ) {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.PUT,
            authorizedJsonEntity(
                new UpdateNotificationSettingsRequest(channel, telegramChatId),
                accessToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void createPurchase(String accessToken) {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    "Groceries",
                    "FOOD",
                    new BigDecimal("12.50"),
                    CurrencyCode.UAH,
                    LocalDate.of(2026, 3, 10),
                    "Fresh Market",
                    "Weekly shop",
                    null
                ),
                accessToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<ReportJobResponse> createReport(
        String accessToken,
        ReportType reportType,
        ReportFormat reportFormat
    ) {
        return restTemplate.exchange(
            "/api/reports",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateReportRequest(2026, 3, reportType, reportFormat), accessToken),
            ReportJobResponse.class
        );
    }

    private ReportJobResponse awaitDone(Long reportJobId, String accessToken) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                ReportJobResponse response = restTemplate.exchange(
                    "/api/reports/" + reportJobId,
                    HttpMethod.GET,
                    authorizedEntity(accessToken),
                    ReportJobResponse.class
                ).getBody();
                assertThat(response).isNotNull();
                assertThat(response.status()).isEqualTo(ReportJobStatus.DONE);
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

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private void drainQueue() {
        String queueUrl = sqsClient.getQueueUrl(
            GetQueueUrlRequest.builder()
                .queueName(awsProperties.getSqs().getQueueName())
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
}
