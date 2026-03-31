package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportDownloadResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.service.NotificationService;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;

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
class ReportNotificationFailureIntegrationTests extends AbstractPostgresIntegrationTest {

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
    void notificationFailureDoesNotBreakSuccessfulReportFlow() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob = createReportJob(accessToken, 2026, 5).getBody();

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .untilAsserted(() -> {
                ReportJobResponse response = restTemplate.exchange(
                    "/api/reports/" + createdJob.id(),
                    HttpMethod.GET,
                    authorizedEntity(accessToken),
                    ReportJobResponse.class
                ).getBody();
                assertThat(response).isNotNull();
                assertThat(response.status()).isEqualTo(ReportJobStatus.DONE);
                assertThat(response.s3Key()).isNotBlank();
            });

        ResponseEntity<ReportDownloadResponse> downloadResponse = restTemplate.exchange(
            "/api/reports/" + createdJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReportDownloadResponse.class
        );

        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receivedEmails()).isEmpty();
        assertThat(receivedTelegramMessages()).isEmpty();
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

    @TestConfiguration
    static class FailingNotificationConfiguration {

        @Bean
        @Primary
        NotificationService notificationService() {
            return new NotificationService() {
                @Override
                public void sendReportReadyNotification(com.blyndov.homebudgetreceiptsmanager.entity.ReportJob reportJob) {
                    throw new IllegalStateException("Simulated notification delivery failure");
                }

                @Override
                public void sendReportFailedNotification(com.blyndov.homebudgetreceiptsmanager.entity.ReportJob reportJob) {
                    throw new IllegalStateException("Simulated notification delivery failure");
                }
            };
        }
    }
}
