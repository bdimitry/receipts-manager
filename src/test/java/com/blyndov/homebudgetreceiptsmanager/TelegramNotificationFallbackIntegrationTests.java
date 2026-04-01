package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.UpdateNotificationSettingsRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.service.NotificationChannelSender;
import com.blyndov.homebudgetreceiptsmanager.service.NotificationMessage;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
        "app.report-jobs.consumer.wait-time-seconds=0",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelegramNotificationFallbackIntegrationTests extends AbstractPostgresIntegrationTest {

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

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        userRepository.deleteAll();
        purgeEmails();
        purgeTelegramMessages();
        drainQueue();
    }

    @Test
    void telegramFailureFallsBackToEmailAndKeepsReportDone() throws Exception {
        String email = uniqueEmail("fallback");
        String accessToken = registerAndLogin(email, "P@ssword123");
        markTelegramConnected(email, "777000999");
        updateNotificationSettings(accessToken, NotificationChannel.TELEGRAM);

        ReportJobResponse createdJob = createReportJob(accessToken, 2026, 6).getBody();

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                var reportJob = reportJobRepository.findById(createdJob.id()).orElseThrow();
                assertThat(reportJob.getStatus()).isEqualTo(ReportJobStatus.DONE);
                assertThat(reportJob.getS3Key()).isNotBlank();
            });

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedEmails()).hasSize(1));

        MimeMessage notification = receivedEmails()[0];
        assertThat(notification.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(notification.getSubject()).contains("ready");
        assertThat(receivedTelegramMessages()).isEmpty();
        assertThat(receivedTelegramDocuments()).isEmpty();
    }

    private void updateNotificationSettings(String accessToken, NotificationChannel channel) {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.PUT,
            authorizedJsonEntity(
                new UpdateNotificationSettingsRequest(channel, null),
                accessToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void markTelegramConnected(String email, String chatId) {
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setTelegramChatId(chatId);
        user.setTelegramConnectedAt(Instant.now());
        userRepository.save(user);
    }

    private ResponseEntity<ReportJobResponse> createReportJob(String accessToken, int year, int month) {
        return restTemplate.exchange(
            "/api/reports/monthly",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateMonthlyReportRequest(year, month), accessToken),
            ReportJobResponse.class
        );
    }

    private String registerAndLogin(String email, String password) {
        restTemplate.postForEntity(
            "/api/auth/register",
            new RegisterRequest(email, password),
            String.class
        );

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest(email, password),
            AuthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().accessToken();
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

    @TestConfiguration
    static class FailingTelegramSenderConfiguration {

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        NotificationChannelSender failingTelegramNotificationChannelSender() {
            return new NotificationChannelSender() {
                @Override
                public NotificationChannel channel() {
                    return NotificationChannel.TELEGRAM;
                }

                @Override
                public boolean isConfigured(com.blyndov.homebudgetreceiptsmanager.entity.User user) {
                    return true;
                }

                @Override
                public void send(
                    com.blyndov.homebudgetreceiptsmanager.entity.ReportJob reportJob,
                    NotificationMessage message
                ) {
                    throw new IllegalStateException("Simulated telegram channel failure");
                }
            };
        }
    }
}
