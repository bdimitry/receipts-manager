package com.blyndov.homebudgetreceiptsmanager.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

public abstract class AbstractPostgresIntegrationTest {

    private static final String TEST_BUCKET_NAME = "home-budget-files-test";
    private static final String TEST_REPORT_QUEUE_NAME = "report-generation-queue-test";
    private static final String TEST_RECEIPT_OCR_QUEUE_NAME = "receipt-ocr-queue-test";
    private static final String TEST_TELEGRAM_BOT_TOKEN = "test-telegram-bot-token";
    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("home_budget_integration_test")
        .withUsername("test")
        .withPassword("test");

    private static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7.2"))
            .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.SQS);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> OCR_SERVICE = new GenericContainer<>(
        new ImageFromDockerfile("home-budget-paddleocr-test", false)
            .withFileFromPath("Dockerfile", Paths.get("docker/paddleocr-service/Dockerfile"))
            .withFileFromPath("requirements.txt", Paths.get("docker/paddleocr-service/requirements.txt"))
            .withFileFromPath("app.py", Paths.get("docker/paddleocr-service/app.py"))
            .withFileFromPath("diagnostics.py", Paths.get("docker/paddleocr-service/diagnostics.py"))
            .withFileFromPath("header_rescue.py", Paths.get("docker/paddleocr-service/header_rescue.py"))
            .withFileFromPath("ocr_engine.py", Paths.get("docker/paddleocr-service/ocr_engine.py"))
            .withFileFromPath("preprocessing.py", Paths.get("docker/paddleocr-service/preprocessing.py"))
            .withFileFromPath("profiles.py", Paths.get("docker/paddleocr-service/profiles.py"))
            .withFileFromPath("response_mapping.py", Paths.get("docker/paddleocr-service/response_mapping.py"))
    )
        .withExposedPorts(8083)
        .withEnv("PADDLE_OCR_PROFILE", "en")
        .withEnv("PADDLE_OCR_PREPROCESSING_ENABLED", "true")
        .waitingFor(
            Wait.forHttp("/health")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(6))
        );

    @SuppressWarnings("resource")
    private static final GenericContainer<?> TELEGRAM_MOCK_SERVICE = new GenericContainer<>(
        new ImageFromDockerfile("home-budget-telegram-mock-test", false)
            .withFileFromPath("Dockerfile", Paths.get("docker/telegram-mock-service/Dockerfile"))
            .withFileFromPath("requirements.txt", Paths.get("docker/telegram-mock-service/requirements.txt"))
            .withFileFromPath("app.py", Paths.get("docker/telegram-mock-service/app.py"))
    )
        .withExposedPorts(8082)
        .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    static {
        POSTGRES.start();
        LOCALSTACK.start();
        GREEN_MAIL.start();
        OCR_SERVICE.start();
        TELEGRAM_MOCK_SERVICE.start();
        initializeAwsResources();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add(
            "aws.endpoint",
            () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString()
        );
        registry.add("aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("aws.s3.bucket-name", () -> TEST_BUCKET_NAME);
        registry.add("aws.sqs.queue-name", () -> TEST_REPORT_QUEUE_NAME);
        registry.add("aws.sqs.receipt-ocr-queue-name", () -> TEST_RECEIPT_OCR_QUEUE_NAME);
        registry.add(
            "security.jwt.secret",
            () -> "test-secret-key-for-jwt-auth-flow-123456789"
        );
        registry.add("security.jwt.access-token-expiration", () -> "PT1H");
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> GREEN_MAIL.getSmtp().getPort());
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        registry.add(
            "app.notifications.email.from",
            () -> "noreply@test.home-budget.local"
        );
        registry.add(
            "app.notifications.telegram.base-url",
            () -> "http://%s:%d".formatted(
                TELEGRAM_MOCK_SERVICE.getHost(),
                TELEGRAM_MOCK_SERVICE.getMappedPort(8082)
            )
        );
        registry.add("app.notifications.telegram.bot-token", () -> TEST_TELEGRAM_BOT_TOKEN);
        registry.add(
            "app.ocr.service.backend",
            () -> "PADDLE"
        );
        registry.add(
            "app.ocr.service.base-url",
            () -> "http://%s:%d".formatted(OCR_SERVICE.getHost(), OCR_SERVICE.getMappedPort(8083))
        );
        registry.add(
            "app.ocr.service.paddle-base-url",
            () -> "http://%s:%d".formatted(OCR_SERVICE.getHost(), OCR_SERVICE.getMappedPort(8083))
        );
        registry.add(
            "app.ocr.service.tesseract-base-url",
            () -> "http://127.0.0.1:18081"
        );
    }

    protected static void purgeEmails() {
        try {
            GREEN_MAIL.purgeEmailFromAllMailboxes();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to purge GreenMail mailboxes", exception);
        }
    }

    protected static MimeMessage[] receivedEmails() {
        return GREEN_MAIL.getReceivedMessages();
    }

    protected static MimeMessage awaitEmailForRecipient(String recipient) {
        return awaitEmail(recipient, null);
    }

    protected static MimeMessage awaitEmail(String recipient, String subjectFragment) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> findEmail(recipient, subjectFragment).isPresent());
        return findEmail(recipient, subjectFragment)
            .orElseThrow(() -> new AssertionError("Expected email was not received for " + recipient));
    }

    private static Optional<MimeMessage> findEmail(String recipient, String subjectFragment) {
        return Arrays.stream(receivedEmails())
            .filter(message -> messageHasRecipient(message, recipient))
            .filter(message -> messageHasSubject(message, subjectFragment))
            .reduce((first, second) -> second);
    }

    private static boolean messageHasRecipient(MimeMessage message, String recipient) {
        try {
            return Arrays.stream(message.getAllRecipients())
                .map(address -> address.toString())
                .collect(Collectors.toSet())
                .contains(recipient);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect test email recipients", exception);
        }
    }

    private static boolean messageHasSubject(MimeMessage message, String subjectFragment) {
        if (subjectFragment == null || subjectFragment.isBlank()) {
            return true;
        }

        try {
            String subject = message.getSubject();
            return subject != null && subject.contains(subjectFragment);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to inspect test email subject", exception);
        }
    }

    protected static void purgeTelegramMessages() {
        sendTelegramMockRequest("DELETE", "/messages");
    }

    protected static List<TelegramMockMessage> receivedTelegramMessages() {
        try {
            HttpResponse<String> response = sendTelegramMockRequest("GET", "/messages");
            return OBJECT_MAPPER.readValue(
                response.body(),
                new TypeReference<List<TelegramMockMessage>>() {
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse telegram mock messages", exception);
        }
    }

    private static HttpResponse<String> sendTelegramMockRequest(String method, String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://%s:%d%s".formatted(
                            TELEGRAM_MOCK_SERVICE.getHost(),
                            TELEGRAM_MOCK_SERVICE.getMappedPort(8082),
                            path
                        )
                    )
                );

            HttpRequest request = switch (method) {
                case "DELETE" -> builder.DELETE().build();
                case "GET" -> builder.GET().build();
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            };

            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to interact with telegram mock service", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling telegram mock service", exception);
        }
    }

    protected record TelegramMockMessage(String token, String chat_id, String text) {
    }

    private static void initializeAwsResources() {
        try (S3Client s3Client = S3Client.builder()
            .region(Region.of(LOCALSTACK.getRegion()))
            .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())
                )
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(TEST_BUCKET_NAME).build());
        }

        try (SqsClient sqsClient = SqsClient.builder()
            .region(Region.of(LOCALSTACK.getRegion()))
            .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())
                )
            )
            .build()) {
            sqsClient.createQueue(CreateQueueRequest.builder().queueName(TEST_REPORT_QUEUE_NAME).build());
            sqsClient.createQueue(CreateQueueRequest.builder().queueName(TEST_RECEIPT_OCR_QUEUE_NAME).build());
        }
    }
}
