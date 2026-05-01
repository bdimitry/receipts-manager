package com.blyndov.homebudgetreceiptsmanager.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GoogleTokenInfoResponse(
    String sub,
    String email,
    @JsonProperty("email_verified") String emailVerified,
    String aud
) {
    public boolean isEmailVerified() {
        return "true".equalsIgnoreCase(emailVerified);
    }
}
