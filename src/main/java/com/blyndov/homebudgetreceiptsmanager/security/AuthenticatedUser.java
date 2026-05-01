package com.blyndov.homebudgetreceiptsmanager.security;

public record AuthenticatedUser(Long id, String email, String role) {
}
