package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseItemRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
class PurchaseIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        purchaseRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createPurchaseReturnsCreatedPurchaseForCurrentUser() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");

        ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    "Groceries",
                    "FOOD",
                    new BigDecimal("123.45"),
                    CurrencyCode.UAH,
                    LocalDate.of(2026, 3, 15),
                    "Fresh Market",
                    "Weekly shopping",
                    null
                ),
                accessToken
            ),
            PurchaseResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().title()).isEqualTo("Groceries");
        assertThat(response.getBody().category()).isEqualTo("FOOD");
        assertThat(response.getBody().amount()).isEqualByComparingTo("123.45");
        assertThat(response.getBody().currency()).isEqualTo(CurrencyCode.UAH);
        assertThat(response.getBody().purchaseDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(response.getBody().storeName()).isEqualTo("Fresh Market");
        assertThat(response.getBody().comment()).isEqualTo("Weekly shopping");
        assertThat(response.getBody().createdAt()).isNotNull();
        assertThat(response.getBody().items()).isEmpty();
    }

    @Test
    void createPurchaseWithItemsComputesAmountAndReturnsPersistedItems() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");

        ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    "Groceries basket",
                    "FOOD",
                    new BigDecimal("1.00"),
                    CurrencyCode.EUR,
                    LocalDate.of(2026, 3, 20),
                    "Fresh Market",
                    "Basket with details",
                    List.of(
                        new PurchaseItemRequest("Milk", new BigDecimal("2"), "pcs", new BigDecimal("1.50"), null),
                        new PurchaseItemRequest("Bread", null, null, null, new BigDecimal("2.20"))
                    )
                ),
                accessToken
            ),
            PurchaseResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().amount()).isEqualByComparingTo("5.20");
        assertThat(response.getBody().currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(response.getBody().items()).hasSize(2);
        assertThat(response.getBody().items().get(0).title()).isEqualTo("Milk");
        assertThat(response.getBody().items().get(0).lineTotal()).isEqualByComparingTo("3.00");
        assertThat(response.getBody().items().get(1).title()).isEqualTo("Bread");
        assertThat(response.getBody().items().get(1).lineTotal()).isEqualByComparingTo("2.20");
    }

    @Test
    void createPurchaseWithoutTokenReturnsUnauthorized() {
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/purchases",
            new CreatePurchaseRequest(
                "Coffee",
                "FOOD",
                new BigDecimal("5.50"),
                CurrencyCode.UAH,
                LocalDate.of(2026, 3, 10),
                "Corner Cafe",
                "Morning coffee",
                null
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createPurchaseWithInvalidDataReturnsBadRequest() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(
                    " ",
                    "",
                    BigDecimal.ZERO,
                    null,
                    null,
                    "Store",
                    "Comment",
                    List.of(new PurchaseItemRequest(" ", null, null, null, null))
                ),
                accessToken
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listPurchasesReturnsOnlyCurrentUsersData() {
        String firstUserToken = registerAndLogin(uniqueEmail("first"), "P@ssword123");
        createPurchase(
            firstUserToken,
            "Milk",
            "FOOD",
            new BigDecimal("3.99"),
            LocalDate.of(2026, 3, 2)
        );

        String secondUserToken = registerAndLogin(uniqueEmail("second"), "P@ssword123");
        createPurchase(
            secondUserToken,
            "Bus ticket",
            "TRANSPORT",
            new BigDecimal("1.70"),
            LocalDate.of(2026, 3, 3)
        );

        ResponseEntity<List<PurchaseResponse>> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.GET,
            authorizedEntity(firstUserToken),
            new ParameterizedTypeReference<>() { }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).title()).isEqualTo("Milk");
    }

    @Test
    void listPurchasesSupportsYearMonthAndCategoryFilters() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        createPurchase(
            accessToken,
            "March groceries",
            "FOOD",
            new BigDecimal("20.00"),
            LocalDate.of(2026, 3, 5)
        );
        createPurchase(
            accessToken,
            "February groceries",
            "FOOD",
            new BigDecimal("18.00"),
            LocalDate.of(2026, 2, 5)
        );
        createPurchase(
            accessToken,
            "March taxi",
            "TRANSPORT",
            new BigDecimal("12.00"),
            LocalDate.of(2025, 3, 5)
        );

        ResponseEntity<List<PurchaseResponse>> response = restTemplate.exchange(
            "/api/purchases?year=2026&month=3&category=FOOD",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            new ParameterizedTypeReference<>() { }
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).title()).isEqualTo("March groceries");
    }

    @Test
    void getOwnPurchaseReturnsOk() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        PurchaseResponse createdPurchase = createPurchase(
            accessToken,
            "Internet bill",
            "UTILITIES",
            new BigDecimal("30.00"),
            LocalDate.of(2026, 3, 1)
        );

        ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
            "/api/purchases/" + createdPurchase.id(),
            HttpMethod.GET,
            authorizedEntity(accessToken),
            PurchaseResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(createdPurchase.id());
        assertThat(response.getBody().title()).isEqualTo("Internet bill");
    }

    @Test
    void getAnotherUsersPurchaseReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        PurchaseResponse createdPurchase = createPurchase(
            ownerToken,
            "Private purchase",
            "MISC",
            new BigDecimal("44.10"),
            LocalDate.of(2026, 3, 20)
        );

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/purchases/" + createdPurchase.id(),
            HttpMethod.GET,
            authorizedEntity(anotherUserToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteOwnPurchaseReturnsNoContent() {
        String accessToken = registerAndLogin(uniqueEmail("buyer"), "P@ssword123");
        PurchaseResponse createdPurchase = createPurchase(
            accessToken,
            "Gym",
            "HEALTH",
            new BigDecimal("50.00"),
            LocalDate.of(2026, 3, 18)
        );

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
            "/api/purchases/" + createdPurchase.id(),
            HttpMethod.DELETE,
            authorizedEntity(accessToken),
            Void.class
        );

        ResponseEntity<String> getResponse = restTemplate.exchange(
            "/api/purchases/" + createdPurchase.id(),
            HttpMethod.GET,
            authorizedEntity(accessToken),
            String.class
        );

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteAnotherUsersPurchaseReturnsNotFound() {
        String ownerToken = registerAndLogin(uniqueEmail("owner"), "P@ssword123");
        PurchaseResponse createdPurchase = createPurchase(
            ownerToken,
            "Hidden purchase",
            "MISC",
            new BigDecimal("15.00"),
            LocalDate.of(2026, 3, 22)
        );

        String anotherUserToken = registerAndLogin(uniqueEmail("intruder"), "P@ssword123");

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/purchases/" + createdPurchase.id(),
            HttpMethod.DELETE,
            authorizedEntity(anotherUserToken),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private PurchaseResponse createPurchase(
        String accessToken,
        String title,
        String category,
        BigDecimal amount,
        LocalDate purchaseDate
    ) {
        ResponseEntity<PurchaseResponse> response = restTemplate.exchange(
            "/api/purchases",
            HttpMethod.POST,
            authorizedJsonEntity(
                new CreatePurchaseRequest(title, category, amount, CurrencyCode.UAH, purchaseDate, "Store", "Comment", null),
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
        return new HttpEntity<>(authorizedHeaders(accessToken));
    }

    private <T> HttpEntity<T> authorizedJsonEntity(T body, String accessToken) {
        HttpHeaders headers = authorizedHeaders(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders authorizedHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
