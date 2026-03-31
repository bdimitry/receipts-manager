package com.blyndov.homebudgetreceiptsmanager.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OcrClientProperties.class, ReceiptOcrConsumerProperties.class})
public class OcrConfig {
}
