package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportDownloadResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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
class ReportJobGenerationIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReportJobRepository reportJobRepository;

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

    @BeforeEach
    void setUp() {
        reportJobRepository.deleteAll();
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
        drainQueue();
        purgeEmails();
        purgeTelegramMessages();
    }

    @Test
    void monthlyCsvCompatibilityFlowStillWorks() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        String ownerToken = registerAndLogin(ownerEmail, "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "12.50", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Fresh Market", "Weekly shop");
        createPurchase(ownerToken, "Bus Card", "TRANSPORT", "15.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 12), "City Transit", "Top up");
        createPurchase(ownerToken, "Old Month", "FOOD", "99.99", CurrencyCode.UAH, LocalDate.of(2026, 2, 28), "Archive Store", "Should be excluded");

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");
        createPurchase(anotherUserToken, "Other User Purchase", "FOOD", "33.33", CurrencyCode.UAH, LocalDate.of(2026, 3, 11), "Other Store", "Should be excluded");

        ResponseEntity<ReportJobResponse> createResponse = restTemplate.exchange(
            "/api/reports/monthly",
            HttpMethod.POST,
            authorizedJsonEntity(new CreateMonthlyReportRequest(2026, 3), ownerToken),
            ReportJobResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().reportType()).isEqualTo(ReportType.MONTHLY_SPENDING);
        assertThat(createResponse.getBody().reportFormat()).isEqualTo(ReportFormat.CSV);

        ReportJobResponse completedJob = awaitDone(createResponse.getBody().id(), ownerToken);
        String csvContent = loadReportAsString(completedJob.s3Key());

        assertThat(completedJob.status()).isEqualTo(ReportJobStatus.DONE);
        assertThat(completedJob.s3Key()).endsWith(".csv");
        assertThat(csvContent).contains("Monthly Spending Report");
        assertThat(csvContent).contains("Period,2026-03");
        assertThat(csvContent).contains("Owner," + ownerEmail);
        assertThat(csvContent).contains("Currencies,UAH");
        assertThat(csvContent).contains("2026-03-10,Groceries,FOOD,Fresh Market,UAH,12.50,Weekly shop");
        assertThat(csvContent).contains("2026-03-12,Bus Card,TRANSPORT,City Transit,UAH,15.00,Top up");
        assertThat(csvContent).contains("Totals by Currency");
        assertThat(csvContent).contains("UAH,27.50");
        assertThat(csvContent).contains("Category Summary");
        assertThat(csvContent).contains("FOOD,UAH,12.50");
        assertThat(csvContent).contains("TRANSPORT,UAH,15.00");
        assertThat(csvContent).doesNotContain("Monthly Total");
        assertThat(csvContent).doesNotContain("Old Month");
        assertThat(csvContent).doesNotContain("Other User Purchase");

        ResponseEntity<ReportDownloadResponse> downloadResponse = restTemplate.exchange(
            "/api/reports/" + completedJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(ownerToken),
            ReportDownloadResponse.class
        );

        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isNotNull();
        assertThat(downloadResponse.getBody().reportType()).isEqualTo(ReportType.MONTHLY_SPENDING);
        assertThat(downloadResponse.getBody().reportFormat()).isEqualTo(ReportFormat.CSV);
        assertThat(downloadResponse.getBody().fileName()).endsWith(".csv");
        assertThat(downloadResponse.getBody().contentType()).isEqualTo("text/csv");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedEmails()).hasSize(1));
        MimeMessage notification = receivedEmails()[0];
        assertThat(notification.getAllRecipients()[0].toString()).isEqualTo(ownerEmail);
        assertThat(notification.getSubject()).contains("ready");
        assertThat(receivedTelegramMessages()).isEmpty();
    }

    @Test
    void monthlyPdfReportIsGeneratedStoredAndDownloadable() throws Exception {
        String ownerEmail = uniqueEmail("owner");
        String ownerToken = registerAndLogin(ownerEmail, "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "12.50", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Fresh Market", "Weekly shop");

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(ownerToken, 2026, 3, ReportType.MONTHLY_SPENDING, ReportFormat.PDF).getBody().id(),
            ownerToken
        );

        byte[] pdfBytes = loadReportBytes(completedJob.s3Key());
        String pdfText = extractPdfText(pdfBytes);

        assertThat(completedJob.s3Key()).endsWith(".pdf");
        assertThat(pdfBytes).isNotEmpty();
        assertThat(pdfText).contains("Monthly Spending Report");
        assertThat(pdfText).contains("Period: 2026-03");
        assertThat(pdfText).contains("Owner: " + ownerEmail);
        assertThat(pdfText).contains("Currencies: UAH");
        assertThat(pdfText).contains("Groceries");
        assertThat(pdfText).contains("Fresh Market");
        assertThat(pdfText).contains("Totals by Currency");

        ResponseEntity<ReportDownloadResponse> downloadResponse = restTemplate.exchange(
            "/api/reports/" + completedJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(ownerToken),
            ReportDownloadResponse.class
        );

        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isNotNull();
        assertThat(downloadResponse.getBody().reportFormat()).isEqualTo(ReportFormat.PDF);
        assertThat(downloadResponse.getBody().fileName()).endsWith(".pdf");
        assertThat(downloadResponse.getBody().contentType()).isEqualTo("application/pdf");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedEmails()).hasSize(1));
        assertThat(receivedEmails()[0].getContent().toString()).contains("PDF");
        assertThat(receivedTelegramMessages()).isEmpty();
    }

    @Test
    void monthlyXlsxReportIsGeneratedStoredAndDownloadable() throws Exception {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "22.40", CurrencyCode.UAH, LocalDate.of(2026, 3, 8), "Fresh Market", "Weekly shop");
        createPurchase(ownerToken, "Metro", "TRANSPORT", "7.60", CurrencyCode.UAH, LocalDate.of(2026, 3, 9), "City Transit", "Trip");

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(ownerToken, 2026, 3, ReportType.MONTHLY_SPENDING, ReportFormat.XLSX).getBody().id(),
            ownerToken
        );

        byte[] xlsxBytes = loadReportBytes(completedJob.s3Key());
        List<String> cells = readWorkbookCells(xlsxBytes);

        assertThat(completedJob.s3Key()).endsWith(".xlsx");
        assertThat(xlsxBytes).isNotEmpty();
        assertThat(cells).contains("Monthly Spending Report");
        assertThat(cells).contains("Period");
        assertThat(cells).contains("2026-03");
        assertThat(cells).contains("Currencies");
        assertThat(cells).contains("UAH");
        assertThat(cells).contains("Groceries");
        assertThat(cells).contains("Metro");
        assertThat(cells).contains("Totals by Currency");
        assertThat(cells).contains("30.00");

        ResponseEntity<ReportDownloadResponse> downloadResponse = restTemplate.exchange(
            "/api/reports/" + completedJob.id() + "/download",
            HttpMethod.GET,
            authorizedEntity(ownerToken),
            ReportDownloadResponse.class
        );

        assertThat(downloadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse.getBody()).isNotNull();
        assertThat(downloadResponse.getBody().reportFormat()).isEqualTo(ReportFormat.XLSX);
        assertThat(downloadResponse.getBody().fileName()).endsWith(".xlsx");
    }

    @Test
    void categorySummaryCsvIncludesOnlyOwnersCurrentMonthData() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "12.50", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Fresh Market", null);
        createPurchase(ownerToken, "Snacks", "FOOD", "3.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 12), "Fresh Market", null);
        createPurchase(ownerToken, "Metro", "TRANSPORT", "15.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 12), "City Transit", null);
        createPurchase(ownerToken, "Old Month", "FOOD", "99.99", CurrencyCode.UAH, LocalDate.of(2026, 2, 12), "Archive Store", null);

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");
        createPurchase(anotherUserToken, "Other User", "FOOD", "1.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Other Store", null);

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(ownerToken, 2026, 3, ReportType.CATEGORY_SUMMARY, ReportFormat.CSV).getBody().id(),
            ownerToken
        );

        String csvContent = loadReportAsString(completedJob.s3Key());
        assertThat(csvContent).contains("Category Summary Report");
        assertThat(csvContent).contains("category,currency,purchaseCount,totalAmount");
        assertThat(csvContent).contains("FOOD,UAH,2,15.50");
        assertThat(csvContent).contains("TRANSPORT,UAH,1,15.00");
        assertThat(csvContent).contains("Totals by Currency");
        assertThat(csvContent).contains("UAH,30.50");
        assertThat(csvContent).doesNotContain("Old Month");
        assertThat(csvContent).doesNotContain("Other User");
    }

    @Test
    void storeSummaryCsvIncludesOnlyOwnersCurrentMonthData() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "12.50", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Fresh Market", null);
        createPurchase(ownerToken, "Snacks", "FOOD", "3.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 12), "Fresh Market", null);
        createPurchase(ownerToken, "Metro", "TRANSPORT", "15.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 12), "City Transit", null);
        createPurchase(ownerToken, "No Store", "OTHER", "5.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 13), null, null);
        createPurchase(ownerToken, "Old Month", "FOOD", "99.99", CurrencyCode.UAH, LocalDate.of(2026, 2, 12), "Archive Store", null);

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");
        createPurchase(anotherUserToken, "Other User", "FOOD", "1.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Other Store", null);

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(ownerToken, 2026, 3, ReportType.STORE_SUMMARY, ReportFormat.CSV).getBody().id(),
            ownerToken
        );

        String csvContent = loadReportAsString(completedJob.s3Key());
        assertThat(csvContent).contains("Store Summary Report");
        assertThat(csvContent).contains("storeName,currency,purchaseCount,totalAmount");
        assertThat(csvContent).contains("Fresh Market,UAH,2,15.50");
        assertThat(csvContent).contains("City Transit,UAH,1,15.00");
        assertThat(csvContent).contains("Unknown Store,UAH,1,5.00");
        assertThat(csvContent).contains("Totals by Currency");
        assertThat(csvContent).contains("UAH,35.50");
        assertThat(csvContent).doesNotContain("Archive Store");
        assertThat(csvContent).doesNotContain("Other User");
    }

    @Test
    void processingWithoutPurchasesGeneratesEmptyButValidReport() {
        String ownerEmail = uniqueEmail("owner");
        String accessToken = registerAndLogin(ownerEmail, "P@ssword123");

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(accessToken, 2026, 4, ReportType.MONTHLY_SPENDING, ReportFormat.CSV).getBody().id(),
            accessToken
        );

        String csvContent = loadReportAsString(completedJob.s3Key());
        assertThat(csvContent).contains("Monthly Spending Report");
        assertThat(csvContent).contains("Period,2026-04");
        assertThat(csvContent).contains("Owner," + ownerEmail);
        assertThat(csvContent).contains("Currencies,No purchases");
        assertThat(csvContent).contains("No purchases found for the selected period");
        assertThat(csvContent).contains("No currency totals available");
        assertThat(csvContent).contains("No category totals available");
    }

    @Test
    void mixedCurrenciesAreSeparatedInsteadOfBeingSummedIntoOneTotal() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");

        createPurchase(ownerToken, "Groceries", "FOOD", "100.00", CurrencyCode.UAH, LocalDate.of(2026, 3, 10), "Fresh Market", null);
        createPurchase(ownerToken, "Software", "SUBSCRIPTIONS", "20.00", CurrencyCode.USD, LocalDate.of(2026, 3, 11), "App Store", null);

        ReportJobResponse completedJob = awaitDone(
            createGenericReportJob(ownerToken, 2026, 3, ReportType.MONTHLY_SPENDING, ReportFormat.CSV).getBody().id(),
            ownerToken
        );

        String csvContent = loadReportAsString(completedJob.s3Key());
        assertThat(csvContent).contains("Totals by Currency");
        assertThat(csvContent).contains("UAH,100.00");
        assertThat(csvContent).contains("USD,20.00");
        assertThat(csvContent).doesNotContain("Monthly Total");
        assertThat(csvContent).contains("2026-03-10,Groceries,FOOD,Fresh Market,UAH,100.00,");
        assertThat(csvContent).contains("2026-03-11,Software,SUBSCRIPTIONS,App Store,USD,20.00,");
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

    private void createPurchase(
        String accessToken,
        String title,
        String category,
        String amount,
        CurrencyCode currency,
        LocalDate purchaseDate,
        String storeName,
        String comment
    ) {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(title, category, new BigDecimal(amount), currency, purchaseDate, storeName, comment, null),
                accessToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<ReportJobResponse> createGenericReportJob(
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

    private String loadReportAsString(String s3Key) {
        return new String(loadReportBytes(s3Key), StandardCharsets.UTF_8);
    }

    private byte[] loadReportBytes(String s3Key) {
        ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(awsProperties.getS3().getBucketName())
                .key(s3Key)
                .build()
        );
        return responseBytes.asByteArray();
    }

    private String extractPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private List<String> readWorkbookCells(byte[] bytes) throws Exception {
        List<String> values = new ArrayList<>();
        DataFormatter dataFormatter = new DataFormatter();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            workbook.forEach(sheet -> {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String value = dataFormatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            values.add(value);
                        }
                    }
                }
            });
        }

        return values;
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
