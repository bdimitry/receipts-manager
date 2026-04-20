package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.client.OcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.PaddleOcrClient;
import com.blyndov.homebudgetreceiptsmanager.client.TesseractOcrClient;
import com.blyndov.homebudgetreceiptsmanager.config.OcrConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

class OcrClientConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(
            RestClientTestConfiguration.class,
            OcrConfig.class,
            TesseractOcrClient.class,
            PaddleOcrClient.class
        )
        .withPropertyValues(
            "app.ocr.service.base-url=http://localhost:8081",
            "app.ocr.service.paddle-base-url=http://localhost:8083"
        );

    @Test
    void defaultsToPaddleBackend() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OcrClient.class);
            assertThat(context.getBean(OcrClient.class)).isInstanceOf(PaddleOcrClient.class);
        });
    }

    @Test
    void switchesToTesseractBackendWhenExplicitlyConfigured() {
        contextRunner
            .withPropertyValues("app.ocr.service.backend=TESSERACT")
            .run(context -> {
                assertThat(context).hasSingleBean(OcrClient.class);
                assertThat(context.getBean(OcrClient.class)).isInstanceOf(TesseractOcrClient.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class RestClientTestConfiguration {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
