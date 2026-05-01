package com.blyndov.homebudgetreceiptsmanager.config;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private Set<String> adminEmails = new LinkedHashSet<>();
    private Google google = new Google();

    public Set<String> getAdminEmails() {
        return adminEmails;
    }

    public void setAdminEmails(Set<String> adminEmails) {
        this.adminEmails = adminEmails;
    }

    public Google getGoogle() {
        return google;
    }

    public void setGoogle(Google google) {
        this.google = google;
    }

    public static class Google {
        private String clientId = "";
        private String tokenInfoUrl = "https://oauth2.googleapis.com/tokeninfo";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getTokenInfoUrl() {
            return tokenInfoUrl;
        }

        public void setTokenInfoUrl(String tokenInfoUrl) {
            this.tokenInfoUrl = tokenInfoUrl;
        }
    }
}
