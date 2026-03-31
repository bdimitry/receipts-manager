package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CurrentUserResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
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
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
class AuthIntegrationTests extends AbstractPostgresIntegrationTest {

    private static final String TEST_EMAIL = "john.doe@example.com";
    private static final String TEST_PASSWORD = "P@ssword123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void registerCreatesUserAndStoresPasswordAsHash() {
        ResponseEntity<CurrentUserResponse> response = register(TEST_EMAIL, TEST_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(TEST_EMAIL);
        assertThat(response.getBody().createdAt()).isNotNull();

        User savedUser = userRepository.findByEmail(TEST_EMAIL).orElseThrow();
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(TEST_PASSWORD);
        assertThat(passwordEncoder.matches(TEST_PASSWORD, savedUser.getPasswordHash())).isTrue();
    }

    @Test
    void registerWithDuplicateEmailReturnsConflict() {
        register(TEST_EMAIL, TEST_PASSWORD);

        ResponseEntity<String> response =
            restTemplate.postForEntity(
                "/api/auth/register",
                new RegisterRequest(TEST_EMAIL, TEST_PASSWORD),
                String.class
            );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginWithValidCredentialsReturnsJwtToken() {
        register(TEST_EMAIL, TEST_PASSWORD);

        ResponseEntity<AuthResponse> response =
            restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(TEST_EMAIL, TEST_PASSWORD),
                AuthResponse.class
            );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void loginWithInvalidPasswordReturnsUnauthorized() {
        register(TEST_EMAIL, TEST_PASSWORD);

        ResponseEntity<String> response =
            restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(TEST_EMAIL, "wrong-password"),
                String.class
            );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentUserEndpointWithoutTokenReturnsUnauthorized() {
        ResponseEntity<String> response =
            restTemplate.getForEntity("/api/users/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentUserEndpointWithValidTokenReturnsCurrentUser() {
        register(TEST_EMAIL, TEST_PASSWORD);

        String accessToken = login(TEST_EMAIL, TEST_PASSWORD).getBody().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<CurrentUserResponse> response =
            restTemplate.exchange(
                "/api/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                CurrentUserResponse.class
            );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(TEST_EMAIL);
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().createdAt()).isNotNull();
    }

    private ResponseEntity<CurrentUserResponse> register(String email, String password) {
        return restTemplate.postForEntity(
            "/api/auth/register",
            new RegisterRequest(email, password),
            CurrentUserResponse.class
        );
    }

    private ResponseEntity<AuthResponse> login(String email, String password) {
        return restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest(email, password),
            AuthResponse.class
        );
    }
}
