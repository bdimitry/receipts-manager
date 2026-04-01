package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.TelegramBotClient;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TelegramNotificationService implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final TelegramBotClient telegramBotClient;
    private final ReportJobService reportJobService;

    public TelegramNotificationService(
        TelegramBotClient telegramBotClient,
        ReportJobService reportJobService
    ) {
        this.telegramBotClient = telegramBotClient;
        this.reportJobService = reportJobService;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public boolean isConfigured(User user) {
        return user.getTelegramChatId() != null
            && !user.getTelegramChatId().isBlank()
            && user.getTelegramConnectedAt() != null;
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

        String payloadText = message.subject() + "\n\n" + message.body();
        if (isReadyForDocument(reportJob)) {
            telegramBotClient.sendDocument(chatId, reportJobService.buildReadyFileContent(reportJob), payloadText);
            return;
        }

        telegramBotClient.sendMessage(chatId, payloadText);
    }

    private boolean isReadyForDocument(ReportJob reportJob) {
        return reportJob.getStatus() == com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus.DONE
            && StringUtils.hasText(reportJob.getS3Key());
    }
}
