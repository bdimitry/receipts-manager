package com.blyndov.homebudgetreceiptsmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.telegram")
public class NotificationTelegramProperties {

    private String baseUrl = "http://localhost:8082";
    private String botToken = "dev-telegram-bot-token";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }
}
