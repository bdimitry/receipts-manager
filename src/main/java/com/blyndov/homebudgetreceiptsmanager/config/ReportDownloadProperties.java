package com.blyndov.homebudgetreceiptsmanager.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.report-jobs.download")
public class ReportDownloadProperties {

    private Duration expiration = Duration.ofMinutes(15);

    public Duration getExpiration() {
        return expiration;
    }

    public void setExpiration(Duration expiration) {
        this.expiration = expiration;
    }
}
