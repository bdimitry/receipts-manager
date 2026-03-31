package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationTelegramProperties;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TelegramNotificationService implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final RestClient restClient;
    private final NotificationTelegramProperties notificationTelegramProperties;

    public TelegramNotificationService(NotificationTelegramProperties notificationTelegramProperties) {
        this.notificationTelegramProperties = notificationTelegramProperties;
        this.restClient = RestClient.builder()
            .baseUrl(notificationTelegramProperties.getBaseUrl())
            .build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public boolean isConfigured(User user) {
        return user.getTelegramChatId() != null && !user.getTelegramChatId().isBlank();
    }

    @Override
    public void send(ReportJob reportJob, NotificationMessage message) {
        String chatId = reportJob.getUser().getTelegramChatId();
        log.info(
            "Sending telegram notification for jobId={}, userId={}, chatId={}",
            reportJob.getId(),
            reportJob.getUser().getId(),
            chatId
        );

        restClient.post()
            .uri("/bot{botToken}/sendMessage", notificationTelegramProperties.getBotToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new TelegramSendMessageRequest(chatId, message.subject() + "\n\n" + message.body()))
            .retrieve()
            .toBodilessEntity();
    }

    private record TelegramSendMessageRequest(String chat_id, String text) {
    }
}
