package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.NotificationSettingsResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.TelegramConnectSessionResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.TelegramConnectionStatusResponse;
import com.blyndov.homebudgetreceiptsmanager.repository.TelegramConnectTokenRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false",
        "app.notifications.telegram.polling-enabled=true",
        "app.notifications.telegram.polling-interval-ms=100"
    }
)
class TelegramConnectIntegrationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TelegramConnectTokenRepository telegramConnectTokenRepository;

    @BeforeEach
    void setUp() {
        telegramConnectTokenRepository.deleteAll();
        userRepository.deleteAll();
        purgeTelegramMessages();
    }

    @Test
    void createConnectSessionAndPollingLinkCorrectChatToCorrectUser() {
        String firstEmail = uniqueEmail("first");
        String secondEmail = uniqueEmail("second");
        String password = "P@ssword123";
        String firstToken = registerAndLogin(firstEmail, password);
        String secondToken = registerAndLogin(secondEmail, password);

        TelegramConnectSessionResponse firstSession = createConnectSession(firstToken).getBody();
        TelegramConnectSessionResponse secondSession = createConnectSession(secondToken).getBody();

        assertThat(firstSession).isNotNull();
        assertThat(firstSession.deepLink()).contains("?start=");
        assertThat(firstSession.botUsername()).isNotBlank();
        assertThat(secondSession).isNotNull();

        String secondConnectToken = secondSession.deepLink().substring(secondSession.deepLink().indexOf("?start=") + 7);
        enqueueTelegramStartUpdate(secondConnectToken, "777000111");

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                TelegramConnectionStatusResponse secondStatus = getTelegramConnectionStatus(secondToken).getBody();
                assertThat(secondStatus).isNotNull();
                assertThat(secondStatus.connected()).isTrue();
                assertThat(secondStatus.connectedAt()).isNotNull();
                assertThat(secondStatus.pendingDeepLink()).isNull();
            });

        TelegramConnectionStatusResponse firstStatus = getTelegramConnectionStatus(firstToken).getBody();
        assertThat(firstStatus).isNotNull();
        assertThat(firstStatus.connected()).isFalse();
        assertThat(firstStatus.pendingDeepLink()).isEqualTo(firstSession.deepLink());

        NotificationSettingsResponse secondSettings = restTemplate.exchange(
            "/api/users/me/notification-settings",
            HttpMethod.GET,
            authorizedEntity(secondToken),
            NotificationSettingsResponse.class
        ).getBody();

        assertThat(secondSettings).isNotNull();
        assertThat(secondSettings.telegramConnected()).isTrue();
        assertThat(secondSettings.telegramChatId()).isEqualTo("777000111");
        assertThat(userRepository.findByEmail(secondEmail).orElseThrow().getTelegramChatId()).isEqualTo("777000111");
    }

    private ResponseEntity<TelegramConnectSessionResponse> createConnectSession(String accessToken) {
        return restTemplate.exchange(
            "/api/users/me/telegram/connect-session",
            HttpMethod.POST,
            authorizedEntity(accessToken),
            TelegramConnectSessionResponse.class
        );
    }

    private ResponseEntity<TelegramConnectionStatusResponse> getTelegramConnectionStatus(String accessToken) {
        return restTemplate.exchange(
            "/api/users/me/telegram/connection",
            HttpMethod.GET,
            authorizedEntity(accessToken),
            TelegramConnectionStatusResponse.class
        );
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
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(headers);
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
