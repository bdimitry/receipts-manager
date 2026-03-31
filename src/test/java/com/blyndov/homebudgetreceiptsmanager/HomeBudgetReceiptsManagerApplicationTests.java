package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.HealthResponse;
import com.blyndov.homebudgetreceiptsmanager.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.report-jobs.consumer.enabled=false",
        "app.receipts.ocr.consumer.enabled=false"
    }
)
class HomeBudgetReceiptsManagerApplicationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReturnsUpStatus() {
        ResponseEntity<HealthResponse> response =
            restTemplate.getForEntity("/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
            new HealthResponse("UP", "home-budget-receipts-manager")
        );
    }

    @Test
    void openApiDocsAreAccessibleWithoutAuthentication() {
        ResponseEntity<String> response =
            restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
        assertThat(response.getBody()).contains("\"openapi\"");
        assertThat(response.getBody()).contains("/api/health");
    }
}
