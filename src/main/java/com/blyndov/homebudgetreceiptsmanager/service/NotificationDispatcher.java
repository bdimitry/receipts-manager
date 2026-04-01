package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

@Service
public class NotificationDispatcher implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final Map<NotificationChannel, NotificationChannelSender> channelSenders;

    public NotificationDispatcher(List<NotificationChannelSender> channelSenders) {
        this.channelSenders = new EnumMap<>(NotificationChannel.class);
        List<NotificationChannelSender> orderedSenders = new ArrayList<>(channelSenders);
        AnnotationAwareOrderComparator.sort(orderedSenders);
        orderedSenders.forEach(channelSender ->
            this.channelSenders.putIfAbsent(channelSender.channel(), channelSender)
        );
    }

    @Override
    public void sendReportReadyNotification(ReportJob reportJob) {
        String reportType = reportType(reportJob);
        String period = period(reportJob);
        dispatch(
            reportJob,
            new NotificationMessage(
                "%s %s report for %s is ready".formatted(
                    capitalize(reportType),
                    reportJob.getReportFormat(),
                    period
                ),
                """
                Your %s report in %s format for %s is ready.
                Report job id: %d
                The report file is attached or delivered directly in your selected channel.
                You can also download it via GET /api/reports/%d/download after authenticating.
                """.formatted(
                    reportType,
                    reportJob.getReportFormat(),
                    period,
                    reportJob.getId(),
                    reportJob.getId()
                ).strip()
            )
        );
    }

    @Override
    public void sendReportFailedNotification(ReportJob reportJob) {
        String reportType = reportType(reportJob);
        String period = period(reportJob);
        dispatch(
            reportJob,
            new NotificationMessage(
                "%s %s report for %s failed".formatted(
                    capitalize(reportType),
                    reportJob.getReportFormat(),
                    period
                ),
                """
                %s report generation in %s format for %s failed.
                Report job id: %d
                Check GET /api/reports/%d for the current status and error details.
                """.formatted(
                    reportType,
                    reportJob.getReportFormat(),
                    period,
                    reportJob.getId(),
                    reportJob.getId()
                ).strip()
            )
        );
    }

    private void dispatch(ReportJob reportJob, NotificationMessage message) {
        User user = reportJob.getUser();
        List<NotificationChannel> deliveryOrder = buildDeliveryOrder(user);
        RuntimeException lastException = null;

        log.info(
            "Dispatching notification for jobId={}, userId={}, preferredChannel={}, fallbackOrder={}",
            reportJob.getId(),
            user.getId(),
            user.getPreferredNotificationChannel(),
            deliveryOrder
        );

        for (NotificationChannel channel : deliveryOrder) {
            NotificationChannelSender sender = channelSenders.get(channel);
            if (sender == null) {
                log.warn(
                    "Notification channel sender is not registered for channel={} and jobId={}",
                    channel,
                    reportJob.getId()
                );
                continue;
            }

            if (!sender.isConfigured(user)) {
                log.info(
                    "Skipping notification channel={} for jobId={} because the user is not configured for it",
                    channel,
                    reportJob.getId()
                );
                continue;
            }

            try {
                log.info(
                    "Attempting notification delivery for jobId={}, userId={}, channel={}",
                    reportJob.getId(),
                    user.getId(),
                    channel
                );
                sender.send(reportJob, message);
                log.info(
                    "Notification delivered successfully for jobId={}, userId={}, channel={}",
                    reportJob.getId(),
                    user.getId(),
                    channel
                );
                return;
            } catch (RuntimeException exception) {
                lastException = exception;
                log.error(
                    "Notification delivery failed for jobId={}, userId={}, channel={}: {}",
                    reportJob.getId(),
                    user.getId(),
                    channel,
                    exception.getMessage(),
                    exception
                );
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        throw new IllegalStateException(
            "No notification channel is configured for user id " + user.getId()
        );
    }

    private List<NotificationChannel> buildDeliveryOrder(User user) {
        NotificationChannel preferredChannel =
            user.getPreferredNotificationChannel() != null
                ? user.getPreferredNotificationChannel()
                : NotificationChannel.EMAIL;

        List<NotificationChannel> deliveryOrder = new ArrayList<>();
        deliveryOrder.add(preferredChannel);

        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!deliveryOrder.contains(channel)) {
                deliveryOrder.add(channel);
            }
        }

        return deliveryOrder;
    }

    private String period(ReportJob reportJob) {
        return YearMonth.of(reportJob.getYear(), reportJob.getMonth()).toString();
    }

    private String reportType(ReportJob reportJob) {
        return reportJob.getReportType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
