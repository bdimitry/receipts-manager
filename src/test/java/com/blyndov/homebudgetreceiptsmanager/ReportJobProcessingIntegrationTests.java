package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.service.ReportJobProcessor;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
class ReportJobProcessingIntegrationTests extends AbstractPostgresIntegrationTest {

    private static final Duration ASYNC_REPORT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Autowired
    private ReportJobRepository reportJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BlockingReportJobProcessor blockingReportJobProcessor;

    @Autowired
    private SqsClient sqsClient;

    @Autowired
    private AwsProperties awsProperties;

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        userRepository.deleteAll();
        drainQueue();
        purgeEmails();
        blockingReportJobProcessor.reset();
    }

    @Test
    void consumerMovesJobFromProcessingToDone() {
        String accessToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReportJobResponse createdJob = createReportJob(accessToken, 2026, 7).getBody();

        Awaitility.await()
            .atMost(ASYNC_REPORT_TIMEOUT)
            .untilAsserted(() -> {
                ReportJob reportJob = reportJobRepository.findById(createdJob.id()).orElseThrow();
                assertThat(reportJob.getStatus()).isEqualTo(ReportJobStatus.PROCESSING);
                assertThat(blockingReportJobProcessor.hasStarted()).isTrue();
            });

        blockingReportJobProcessor.allowCompletion();

        Awaitility.await()
            .atMost(ASYNC_REPORT_TIMEOUT)
            .untilAsserted(() -> assertThat(
                reportJobRepository.findById(createdJob.id()).orElseThrow().getStatus()
            ).isEqualTo(ReportJobStatus.DONE));
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
    static class BlockingProcessorConfiguration {

        @Bean
        @Primary
        BlockingReportJobProcessor blockingReportJobProcessor() {
            return new BlockingReportJobProcessor();
        }
    }

    static class BlockingReportJobProcessor implements ReportJobProcessor {

        private volatile CountDownLatch startedLatch = new CountDownLatch(1);
        private volatile CountDownLatch releaseLatch = new CountDownLatch(1);

        @Override
        public String process(ReportJob reportJob) {
            startedLatch.countDown();
            try {
                releaseLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting in test processor", exception);
            }
            return "reports/test-blocking.csv";
        }

        void reset() {
            startedLatch = new CountDownLatch(1);
            releaseLatch = new CountDownLatch(1);
        }

        boolean hasStarted() {
            return startedLatch.getCount() == 0;
        }

        void allowCompletion() {
            releaseLatch.countDown();
        }
    }
}
