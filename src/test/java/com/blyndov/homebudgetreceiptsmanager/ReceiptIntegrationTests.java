package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptOcrStatus;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
class ReceiptIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReceiptRepository receiptRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private AwsProperties awsProperties;

    @BeforeEach
    void setUp() {
        receiptRepository.deleteAll();
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
        clearBucket();
    }

    @Test
    void uploadReceiptStoresMetadataAndUploadsObjectToS3() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        PurchaseResponse purchase = createPurchase(accessToken, "Laptop sleeve");

        ResponseEntity<ReceiptResponse> response = uploadReceipt(
            accessToken,
            "receipt.pdf",
            MediaType.APPLICATION_PDF,
            "pdf-content".getBytes(),
            purchase.id()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().purchaseId()).isEqualTo(purchase.id());
        assertThat(response.getBody().originalFileName()).isEqualTo("receipt.pdf");
        assertThat(response.getBody().contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(response.getBody().fileSize()).isEqualTo(11L);
        assertThat(response.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(response.getBody().s3Key()).startsWith("receipts/");
        assertThat(response.getBody().uploadedAt()).isNotNull();
        assertThat(response.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.NEW);
        assertThat(response.getBody().parsedStoreName()).isNull();
        assertThat(response.getBody().parsedTotalAmount()).isNull();
        assertThat(response.getBody().parsedPurchaseDate()).isNull();
        assertThat(response.getBody().parsedLineItemCount()).isZero();
        assertThat(response.getBody().ocrErrorMessage()).isNull();
        assertThat(response.getBody().ocrProcessedAt()).isNull();

        assertThat(
            s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(response.getBody().s3Key())
                    .build()
            ).contentLength()
        ).isEqualTo(11L);
    }

    @Test
    void uploadReceiptWithoutTokenReturnsUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.pdf", MediaType.APPLICATION_PDF, "content".getBytes(), null, CurrencyCode.UAH, null),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void uploadEmptyFileReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.pdf", MediaType.APPLICATION_PDF, new byte[0], null, CurrencyCode.UAH, accessToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadWithAnotherUsersPurchaseReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        PurchaseResponse purchase = createPurchase(ownerToken, "Owner purchase");
        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity(
                "receipt.png",
                MediaType.IMAGE_PNG,
                "png".getBytes(),
                purchase.id(),
                CurrencyCode.UAH,
                anotherUserToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReceiptsReturnsOnlyCurrentUsersReceipts() {
        String firstUserToken = registerAndLogin(uniqueEmail("first"), "P@ssword123");
        uploadReceipt(firstUserToken, "first.pdf", MediaType.APPLICATION_PDF, "first".getBytes(), null);

        String secondUserToken = registerAndLogin(uniqueEmail("second"), "P@ssword123");
        uploadReceipt(secondUserToken, "second.pdf", MediaType.APPLICATION_PDF, "second".getBytes(), null);

        ResponseEntity<List<ReceiptResponse>> response = restTemplate.exchange(
            "/api/receipts",
            HttpMethod.GET,
            authorizedEntity(firstUserToken),
            new ParameterizedTypeReference<>() { }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).originalFileName()).isEqualTo("first.pdf");
    }

    @Test
    void uploadWithPurchaseCurrencyMismatchReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        PurchaseResponse purchase = createPurchase(accessToken, "Mismatch", CurrencyCode.USD);

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.png", MediaType.IMAGE_PNG, "png".getBytes(), purchase.id(), CurrencyCode.UAH, accessToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getOwnReceiptReturnsOk() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        ReceiptResponse uploadedReceipt = uploadReceipt(
            accessToken,
            "receipt.jpg",
            MediaType.IMAGE_JPEG,
            "jpeg".getBytes(),
            null
        ).getBody();

        ResponseEntity<ReceiptResponse> response = restTemplate.exchange(
            "/api/receipts/" + uploadedReceipt.id(),
            HttpMethod.GET,
            authorizedEntity(accessToken),
            ReceiptResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(uploadedReceipt.id());
        assertThat(response.getBody().originalFileName()).isEqualTo("receipt.jpg");
        assertThat(response.getBody().ocrStatus()).isEqualTo(ReceiptOcrStatus.NEW);
    }

    @Test
    void getAnotherUsersReceiptReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        ReceiptResponse uploadedReceipt = uploadReceipt(
            ownerToken,
            "secret.pdf",
            MediaType.APPLICATION_PDF,
            "secret".getBytes(),
            null
        ).getBody();

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/" + uploadedReceipt.id(),
            HttpMethod.GET,
            authorizedEntity(anotherUserToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void uploadWithUnsupportedContentTypeReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity("receipt.txt", MediaType.TEXT_PLAIN, "hello".getBytes(), null, CurrencyCode.UAH, accessToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ReceiptResponse> uploadReceipt(
        String accessToken,
        String filename,
        MediaType mediaType,
        byte[] content,
        Long purchaseId
    ) {
        return uploadReceipt(accessToken, filename, mediaType, content, purchaseId, CurrencyCode.UAH);
    }

    private ResponseEntity<ReceiptResponse> uploadReceipt(
        String accessToken,
        String filename,
        MediaType mediaType,
        byte[] content,
        Long purchaseId,
        CurrencyCode currency
    ) {
        return restTemplate.exchange(
            "/api/receipts/upload",
            HttpMethod.POST,
            multipartEntity(filename, mediaType, content, purchaseId, currency, accessToken),
            ReceiptResponse.class
        );
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
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType);
        HttpEntity<ByteArrayResource> fileEntity = new HttpEntity<>(
            new NamedByteArrayResource(filename, content),
            fileHeaders
        );

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileEntity);
        body.add("currency", currency.name());
        if (purchaseId != null) {
            body.add("purchaseId", purchaseId.toString());
        }

        return new HttpEntity<>(body, headers);
    }

    private PurchaseResponse createPurchase(String accessToken, String title) {
        return createPurchase(accessToken, title, CurrencyCode.UAH);
    }

    private PurchaseResponse createPurchase(String accessToken, String title, CurrencyCode currency) {
        ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    title,
                    "SHOPPING",
                    new BigDecimal("99.99"),
                    currency,
                    LocalDate.of(2026, 3, 30),
                    "Store",
                    "Comment",
                    null
                ),
                accessToken
            ),
            PurchaseResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
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
