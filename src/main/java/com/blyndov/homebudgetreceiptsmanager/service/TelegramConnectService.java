package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationTelegramProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.TelegramConnectSessionResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.TelegramConnectionStatusResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.TelegramConnectToken;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.repository.TelegramConnectTokenRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class TelegramConnectService {

    private static final Logger log = LoggerFactory.getLogger(TelegramConnectService.class);

    private final AuthService authService;
    private final UserRepository userRepository;
    private final TelegramConnectTokenRepository telegramConnectTokenRepository;
    private final NotificationTelegramProperties telegramProperties;

    public TelegramConnectService(
        AuthService authService,
        UserRepository userRepository,
        TelegramConnectTokenRepository telegramConnectTokenRepository,
        NotificationTelegramProperties telegramProperties
    ) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.telegramConnectTokenRepository = telegramConnectTokenRepository;
        this.telegramProperties = telegramProperties;
    }

    @Transactional
    public TelegramConnectSessionResponse createCurrentUserConnectSession() {
        User user = authService.getCurrentAuthenticatedUser();
        telegramConnectTokenRepository.deleteAllByUser_Id(user.getId());

        TelegramConnectToken connectToken = new TelegramConnectToken();
        connectToken.setUser(user);
        connectToken.setToken(UUID.randomUUID().toString().replace("-", ""));
        connectToken.setExpiresAt(Instant.now().plus(telegramProperties.getConnectTokenTtl()));
        telegramConnectTokenRepository.save(connectToken);

        log.info(
            "Created telegram connect token for userId={}, expiresAt={}",
            user.getId(),
            connectToken.getExpiresAt()
        );

        return mapToSessionResponse(connectToken);
    }

    public TelegramConnectionStatusResponse getCurrentUserConnectionStatus() {
        User user = authService.getCurrentAuthenticatedUser();
        TelegramConnectToken pending = telegramConnectTokenRepository
            .findFirstByUser_IdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(user.getId(), Instant.now())
            .orElse(null);

        return mapToStatusResponse(user, pending);
    }

    @Transactional
    public boolean connectChatByToken(String token, String chatId) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(chatId)) {
            return false;
        }

        TelegramConnectToken connectToken = telegramConnectTokenRepository.findByToken(token.trim()).orElse(null);
        if (connectToken == null) {
            log.info("Ignoring telegram connect attempt for unknown token");
            return false;
        }

        if (connectToken.getUsedAt() != null || connectToken.getExpiresAt().isBefore(Instant.now())) {
            log.info(
                "Ignoring telegram connect attempt for tokenId={} because it is usedOrExpired",
                connectToken.getId()
            );
            return false;
        }

        User user = connectToken.getUser();
        user.setTelegramChatId(chatId.trim());
        user.setTelegramConnectedAt(Instant.now());
        userRepository.save(user);

        connectToken.setUsedAt(Instant.now());
        telegramConnectTokenRepository.save(connectToken);

        log.info(
            "Telegram chat linked successfully for userId={}, chatId={}",
            user.getId(),
            chatId
        );
        return true;
    }

    private TelegramConnectSessionResponse mapToSessionResponse(TelegramConnectToken connectToken) {
        return new TelegramConnectSessionResponse(
            telegramProperties.getBotUsername(),
            buildDeepLink(connectToken.getToken()),
            connectToken.getExpiresAt()
        );
    }

    private TelegramConnectionStatusResponse mapToStatusResponse(User user, TelegramConnectToken pending) {
        boolean connected = isTelegramConnected(user);
        return new TelegramConnectionStatusResponse(
            connected,
            user.getTelegramConnectedAt(),
            telegramProperties.getBotUsername(),
            connected || pending == null ? null : buildDeepLink(pending.getToken()),
            connected || pending == null ? null : pending.getExpiresAt()
        );
    }

    private String buildDeepLink(String token) {
        return "https://t.me/%s?start=%s".formatted(telegramProperties.getBotUsername(), token);
    }

    private boolean isTelegramConnected(User user) {
        return StringUtils.hasText(user.getTelegramChatId()) && user.getTelegramConnectedAt() != null;
    }
}
