package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.TelegramBotClient;
import com.blyndov.homebudgetreceiptsmanager.client.TelegramBotClient.TelegramMessage;
import com.blyndov.homebudgetreceiptsmanager.client.TelegramBotClient.TelegramUpdate;
import com.blyndov.homebudgetreceiptsmanager.config.NotificationTelegramProperties;
import com.blyndov.homebudgetreceiptsmanager.entity.TelegramPollingState;
import com.blyndov.homebudgetreceiptsmanager.repository.TelegramPollingStateRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(name = "app.notifications.telegram.polling-enabled", havingValue = "true", matchIfMissing = true)
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramBotClient telegramBotClient;
    private final TelegramConnectService telegramConnectService;
    private final TelegramPollingStateRepository telegramPollingStateRepository;
    private final NotificationTelegramProperties telegramProperties;

    public TelegramPollingService(
        TelegramBotClient telegramBotClient,
        TelegramConnectService telegramConnectService,
        TelegramPollingStateRepository telegramPollingStateRepository,
        NotificationTelegramProperties telegramProperties
    ) {
        this.telegramBotClient = telegramBotClient;
        this.telegramConnectService = telegramConnectService;
        this.telegramPollingStateRepository = telegramPollingStateRepository;
        this.telegramProperties = telegramProperties;
    }

    @Scheduled(fixedDelayString = "${app.notifications.telegram.polling-interval-ms:3000}")
    @Transactional
    public void poll() {
        TelegramPollingState state = telegramPollingStateRepository.findById(TelegramPollingState.DEFAULT_ID)
            .orElseGet(this::newState);

        Long offset = state.getLastUpdateId() == null ? null : state.getLastUpdateId() + 1;
        List<TelegramUpdate> updates = telegramBotClient.getUpdates(offset, telegramProperties.getMaxUpdates());
        if (updates.isEmpty()) {
            if (state.getId() == null) {
                telegramPollingStateRepository.save(state);
            }
            return;
        }

        long maxUpdateId = state.getLastUpdateId() == null ? 0 : state.getLastUpdateId();
        for (TelegramUpdate update : updates) {
            maxUpdateId = Math.max(maxUpdateId, update.update_id());
            processUpdate(update);
        }

        state.setLastUpdateId(maxUpdateId);
        telegramPollingStateRepository.save(state);
    }

    private void processUpdate(TelegramUpdate update) {
        TelegramMessage message = update.message();
        if (message == null || !StringUtils.hasText(message.text()) || message.chat() == null) {
            return;
        }

        String text = message.text().trim();
        if (!text.startsWith("/start")) {
            return;
        }

        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
            log.info("Ignoring telegram /start without connect token");
            return;
        }

        telegramConnectService.connectChatByToken(parts[1].trim(), message.chat().id());
    }

    private TelegramPollingState newState() {
        TelegramPollingState state = new TelegramPollingState();
        state.setId(TelegramPollingState.DEFAULT_ID);
        return state;
    }
}
