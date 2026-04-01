package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationEmailProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportFileContent;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import jakarta.mail.MessagingException;
import org.springframework.core.io.ByteArrayResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmailNotificationService implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender javaMailSender;
    private final NotificationEmailProperties notificationEmailProperties;
    private final ReportJobService reportJobService;

    public EmailNotificationService(
        JavaMailSender javaMailSender,
        NotificationEmailProperties notificationEmailProperties,
        ReportJobService reportJobService
    ) {
        this.javaMailSender = javaMailSender;
        this.notificationEmailProperties = notificationEmailProperties;
        this.reportJobService = reportJobService;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean isConfigured(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    @Override
    public void send(ReportJob reportJob, NotificationMessage message) {
        String recipient = reportJob.getUser().getEmail();
        log.info(
            "Attempting to send email notification for jobId={}, userId={}, recipient={}",
            reportJob.getId(),
            reportJob.getUser().getId(),
            recipient
        );

        try {
            var mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(notificationEmailProperties.getFrom());
            helper.setTo(recipient);
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);

            if (isReadyForAttachment(reportJob)) {
                ReportFileContent reportFileContent = reportJobService.buildReadyFileContent(reportJob);
                helper.addAttachment(
                    reportFileContent.fileName(),
                    new ByteArrayResource(reportFileContent.content()),
                    reportFileContent.contentType()
                );
            }

            javaMailSender.send(mimeMessage);

            log.info(
                "Email notification sent for jobId={}, userId={}, recipient={}",
                reportJob.getId(),
                reportJob.getUser().getId(),
                recipient
            );
        } catch (MessagingException exception) {
            log.error(
                "Email notification failed for jobId={}, userId={}, recipient={}: {}",
                reportJob.getId(),
                reportJob.getUser().getId(),
                recipient,
                exception.getMessage(),
                exception
            );
            throw new IllegalStateException("Email notification delivery failed", exception);
        } catch (RuntimeException exception) {
            log.error(
                "Email notification failed for jobId={}, userId={}, recipient={}: {}",
                reportJob.getId(),
                reportJob.getUser().getId(),
                recipient,
                exception.getMessage(),
                exception
            );
            throw exception;
        }
    }

    private boolean isReadyForAttachment(ReportJob reportJob) {
        return reportJob.getStatus() == com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus.DONE
            && StringUtils.hasText(reportJob.getS3Key());
    }
}
