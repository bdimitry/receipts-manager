package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.AuthProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.AuthResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.CurrentUserResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.GoogleAuthRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.GoogleTokenInfoResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.LoginRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.RegisterRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.entity.UserRole;
import com.blyndov.homebudgetreceiptsmanager.exception.EmailAlreadyExistsException;
import com.blyndov.homebudgetreceiptsmanager.exception.InvalidCredentialsException;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import com.blyndov.homebudgetreceiptsmanager.security.AuthenticatedUser;
import com.blyndov.homebudgetreceiptsmanager.security.JwtService;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final RestClient restClient;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        AuthProperties authProperties,
        RestClient.Builder restClientBuilder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
        this.restClient = restClientBuilder.build();
    }

    @Transactional
    public CurrentUserResponse register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException("User with email already exists");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(resolveRole(normalizedEmail));
        user.setPreferredNotificationChannel(NotificationChannel.EMAIL);
        user.setCreatedAt(Instant.now());

        User savedUser = userRepository.save(user);
        return mapToCurrentUserResponse(savedUser);
    }

    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        return new AuthResponse(jwtService.generateAccessToken(user), "Bearer");
    }

    @Transactional
    public AuthResponse loginWithGoogle(GoogleAuthRequest request) {
        GoogleTokenInfoResponse tokenInfo = verifyGoogleCredential(request.credential());
        String normalizedEmail = normalizeEmail(tokenInfo.email());

        User user = userRepository.findByGoogleSubject(tokenInfo.sub())
            .or(() -> userRepository.findByEmail(normalizedEmail))
            .map(existingUser -> linkGoogleAccount(existingUser, tokenInfo.sub()))
            .orElseGet(() -> createGoogleUser(normalizedEmail, tokenInfo.sub()));

        return new AuthResponse(jwtService.generateAccessToken(user), "Bearer");
    }

    public CurrentUserResponse getCurrentUser() {
        return mapToCurrentUserResponse(getCurrentAuthenticatedUser());
    }

    public User getCurrentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new InvalidCredentialsException("Authentication is required");
        }

        return userRepository.findByEmail(principal.email())
            .orElseThrow(() -> new InvalidCredentialsException("Authentication is required"));
    }

    private CurrentUserResponse mapToCurrentUserResponse(User user) {
        UserRole role = user.getRole() == null ? UserRole.USER : user.getRole();
        return new CurrentUserResponse(
            user.getId(),
            user.getEmail(),
            user.getCreatedAt(),
            role.name(),
            role == UserRole.ADMIN
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserRole resolveRole(String normalizedEmail) {
        return authProperties.getAdminEmails().stream()
            .map(this::normalizeEmail)
            .anyMatch(normalizedEmail::equals)
            ? UserRole.ADMIN
            : UserRole.USER;
    }

    private User linkGoogleAccount(User user, String googleSubject) {
        if (user.getGoogleSubject() == null) {
            user.setGoogleSubject(googleSubject);
        }
        if (user.getRole() == null) {
            user.setRole(resolveRole(user.getEmail()));
        }
        return user;
    }

    private User createGoogleUser(String normalizedEmail, String googleSubject) {
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setGoogleSubject(googleSubject);
        user.setRole(resolveRole(normalizedEmail));
        user.setPreferredNotificationChannel(NotificationChannel.EMAIL);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private GoogleTokenInfoResponse verifyGoogleCredential(String credential) {
        String clientId = authProperties.getGoogle().getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidCredentialsException("Google sign-in is not configured");
        }

        try {
            GoogleTokenInfoResponse tokenInfo = restClient.get()
                .uri(authProperties.getGoogle().getTokenInfoUrl() + "?id_token={credential}", credential)
                .retrieve()
                .body(GoogleTokenInfoResponse.class);

            if (
                tokenInfo == null ||
                    tokenInfo.sub() == null ||
                    tokenInfo.email() == null ||
                    !tokenInfo.isEmailVerified() ||
                    !clientId.equals(tokenInfo.aud())
            ) {
                throw new InvalidCredentialsException("Invalid Google credential");
            }

            return tokenInfo;
        } catch (RestClientException exception) {
            throw new InvalidCredentialsException("Invalid Google credential");
        }
    }
}
