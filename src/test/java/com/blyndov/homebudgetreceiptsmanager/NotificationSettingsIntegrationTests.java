package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ErrorResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.NotificationSettingsResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.UpdateNotificationSettingsRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false",
        "app.notifications.telegram.polling-enabled=false"
    }
)
class NotificationSettingsIntegrationTests extends AbstractPostgresIntegrationTest {

    private static final String TEST_EMAIL = "notification-settings@example.com";
    private static final String TEST_PASSWORD = "P@ssword123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        purgeEmails();
        purgeTelegramMessages();
    }

    @Test
    void currentUserNotificationSettingsReturnEmailAsDefault() {
        register(TEST_EMAIL, TEST_PASSWORD);
        String accessToken = login(TEST_EMAIL, TEST_PASSWORD).getBody().accessToken();

        ResponseEntity<NotificationSettingsResponse> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            NotificationSettingsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(TEST_EMAIL);
        assertThat(response.getBody().telegramChatId()).isNull();
        assertThat(response.getBody().telegramConnected()).isFalse();
        assertThat(response.getBody().telegramConnectedAt()).isNull();
        assertThat(response.getBody().preferredNotificationChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void updateNotificationSettingsToEmailKeepsTelegramDisconnected() {
        register(TEST_EMAIL, TEST_PASSWORD);
        String accessToken = login(TEST_EMAIL, TEST_PASSWORD).getBody().accessToken();

        ResponseEntity<NotificationSettingsResponse> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.PUT,
            authorizedJsonEntity(
                new UpdateNotificationSettingsRequest(NotificationChannel.EMAIL, "123456789"),
                accessToken
            ),
            NotificationSettingsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().preferredNotificationChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(response.getBody().telegramChatId()).isNull();
        assertThat(response.getBody().telegramConnected()).isFalse();

        var savedUser = userRepository.findByEmail(TEST_EMAIL).orElseThrow();
        assertThat(savedUser.getPreferredNotificationChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(savedUser.getTelegramChatId()).isNull();
    }

    @Test
    void updateNotificationSettingsToTelegramWithoutConnectionReturnsBadRequest() {
        register(TEST_EMAIL, TEST_PASSWORD);
        String accessToken = login(TEST_EMAIL, TEST_PASSWORD).getBody().accessToken();

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.PUT,
            authorizedJsonEntity(
                new UpdateNotificationSettingsRequest(NotificationChannel.TELEGRAM, null),
                accessToken
            ),
            ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("Connect Telegram");
    }

    @Test
    void staleManualTelegramValueDoesNotCountAsConnected() {
        register(TEST_EMAIL, TEST_PASSWORD);
        String accessToken = login(TEST_EMAIL, TEST_PASSWORD).getBody().accessToken();

        User user = userRepository.findByEmail(TEST_EMAIL).orElseThrow();
        user.setTelegramChatId("@legacy_username");
        user.setTelegramConnectedAt(null);
        userRepository.save(user);

        ResponseEntity<NotificationSettingsResponse> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            NotificationSettingsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().telegramChatId()).isEqualTo("@legacy_username");
        assertThat(response.getBody().telegramConnected()).isFalse();
        assertThat(response.getBody().telegramConnectedAt()).isNull();
    }

    @Test
    void updateNotificationSettingsWithoutTokenReturnsUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.PUT,
            new HttpEntity<>(
                new UpdateNotificationSettingsRequest(NotificationChannel.EMAIL, null),
                jsonHeaders()
            ),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> register(String email, String password) {
        return restTemplate.postForEntity(
            "/api/auth/register",
            new RegisterRequest(email, password),
            String.class
        );
    }

    private ResponseEntity<AuthResponse> login(String email, String password) {
        return restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest(email, password),
            AuthResponse.class
        );
    }

    private HttpEntity<Void> authorizedEntity(String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    private <T> HttpEntity<T> authorizedJsonEntity(T body, String accessToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
