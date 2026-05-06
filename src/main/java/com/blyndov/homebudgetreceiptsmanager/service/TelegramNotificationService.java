package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationTelegramProperties;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

        if (message.attachment() != null) {
            sendDocument(chatId, message);
            return;
        }

        sendMessage(chatId, message.subject() + "\n\n" + message.body());
    }

    private void sendMessage(String chatId, String text) {
        restClient.post()
            .uri("/bot{botToken}/sendMessage", notificationTelegramProperties.getBotToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new TelegramSendMessageRequest(chatId, text))
            .retrieve()
            .toBodilessEntity();
    }

    private void sendDocument(String chatId, NotificationMessage message) {
        NotificationAttachment attachment = message.attachment();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("chat_id", chatId);
        body.add("caption", truncateCaption(message.subject() + "\n\n" + message.body()));
        body.add("document", buildDocumentPart(attachment));

        restClient.post()
            .uri("/bot{botToken}/sendDocument", notificationTelegramProperties.getBotToken())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    private HttpEntity<ByteArrayResource> buildDocumentPart(NotificationAttachment attachment) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(attachment.contentType()));
        ByteArrayResource resource = new ByteArrayResource(attachment.content()) {
            @Override
            public String getFilename() {
                return attachment.fileName();
            }
        };
        return new HttpEntity<>(resource, headers);
    }

    private String truncateCaption(String caption) {
        int telegramCaptionLimit = 1024;
        if (caption.length() <= telegramCaptionLimit) {
            return caption;
        }
        return caption.substring(0, telegramCaptionLimit - 1).stripTrailing() + "...";
    }

    private record TelegramSendMessageRequest(String chat_id, String text) {
    }
}
