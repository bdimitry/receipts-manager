package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.NotificationEmailProperties;
import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationService implements NotificationChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender javaMailSender;
    private final NotificationEmailProperties notificationEmailProperties;

    public EmailNotificationService(
        JavaMailSender javaMailSender,
        NotificationEmailProperties notificationEmailProperties
    ) {
        this.javaMailSender = javaMailSender;
        this.notificationEmailProperties = notificationEmailProperties;
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
            MimeMessage mailMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mailMessage, message.attachment() != null, "UTF-8");
            helper.setFrom(notificationEmailProperties.getFrom());
            helper.setTo(recipient);
            helper.setSubject(message.subject());
            helper.setText(message.body());

            NotificationAttachment attachment = message.attachment();
            if (attachment != null) {
                helper.addAttachment(
                    attachment.fileName(),
                    new ByteArrayResource(attachment.content()),
                    attachment.contentType()
                );
            }

            javaMailSender.send(mailMessage);

            log.info(
                "Email notification sent for jobId={}, userId={}, recipient={}",
                reportJob.getId(),
                reportJob.getUser().getId(),
                recipient
            );
        } catch (MessagingException exception) {
            throw new IllegalStateException("Failed to build email notification", exception);
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
}
