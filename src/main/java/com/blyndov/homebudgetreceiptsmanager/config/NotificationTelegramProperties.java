package com.blyndov.homebudgetreceiptsmanager.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.telegram")
public class NotificationTelegramProperties {

    private String baseUrl = "http://localhost:8082";
    private String botToken = "dev-telegram-bot-token";
    private String botUsername = "home_budget_receipts_bot";
    private boolean pollingEnabled = true;
    private long pollingIntervalMs = 3000;
    private int maxUpdates = 20;
    private Duration connectTokenTtl = Duration.ofMinutes(15);

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

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public boolean isPollingEnabled() {
        return pollingEnabled;
    }

    public void setPollingEnabled(boolean pollingEnabled) {
        this.pollingEnabled = pollingEnabled;
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    public void setPollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
    }

    public int getMaxUpdates() {
        return maxUpdates;
    }

    public void setMaxUpdates(int maxUpdates) {
        this.maxUpdates = maxUpdates;
    }

    public Duration getConnectTokenTtl() {
        return connectTokenTtl;
    }

    public void setConnectTokenTtl(Duration connectTokenTtl) {
        this.connectTokenTtl = connectTokenTtl;
    }
}
