package com.blyndov.homebudgetreceiptsmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.email")
public class NotificationEmailProperties {

    private String from = "noreply@home-budget.local";

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
