package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationTelegramProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportFileContent;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TelegramBotClient {

    private final RestClient restClient;
    private final NotificationTelegramProperties notificationTelegramProperties;

    public TelegramBotClient(NotificationTelegramProperties notificationTelegramProperties) {
        this.notificationTelegramProperties = notificationTelegramProperties;
        this.restClient = RestClient.builder()
            .baseUrl(notificationTelegramProperties.getBaseUrl())
            .build();
    }

    public List<TelegramUpdate> getUpdates(Long offset, int limit) {
        TelegramApiResponse<List<TelegramUpdate>> response = restClient.get()
            .uri(uriBuilder -> {
                var builder = uriBuilder
                    .path("/bot{botToken}/getUpdates")
                    .queryParam("limit", limit)
                    .queryParam("timeout", 0);

                if (offset != null) {
                    builder.queryParam("offset", offset);
                }

                return builder.build(notificationTelegramProperties.getBotToken());
            })
            .retrieve()
            .body(new org.springframework.core.ParameterizedTypeReference<TelegramApiResponse<List<TelegramUpdate>>>() {
            });

        return response != null && response.result() != null ? response.result() : List.of();
    }

    public void sendMessage(String chatId, String text) {
        restClient.post()
            .uri("/bot{botToken}/sendMessage", notificationTelegramProperties.getBotToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SendMessageRequest(chatId, text))
            .retrieve()
            .toBodilessEntity();
    }

    public void sendDocument(String chatId, ReportFileContent reportFileContent, String caption) {
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("chat_id", chatId);
        bodyBuilder.part("caption", caption);
        bodyBuilder.part(
            "document",
            new ByteArrayResource(reportFileContent.content()) {
                @Override
                public String getFilename() {
                    return reportFileContent.fileName();
                }
            }
        ).contentType(MediaType.parseMediaType(reportFileContent.contentType()));

        restClient.post()
            .uri("/bot{botToken}/sendDocument", notificationTelegramProperties.getBotToken())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(bodyBuilder.build())
            .retrieve()
            .toBodilessEntity();
    }

    public record TelegramApiResponse<T>(boolean ok, T result) {
    }

    public record TelegramUpdate(long update_id, TelegramMessage message) {
    }

    public record TelegramMessage(String text, TelegramChat chat) {
    }

    public record TelegramChat(String id) {
    }

    private record SendMessageRequest(String chat_id, String text) {
    }
}
