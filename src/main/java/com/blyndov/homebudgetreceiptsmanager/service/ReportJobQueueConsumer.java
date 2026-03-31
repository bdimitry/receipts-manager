package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.ReportGenerationMessage;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.config.ReportJobConsumerProperties;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(name = "app.report-jobs.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ReportJobQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportJobQueueConsumer.class);

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    private final ReportJobConsumerProperties consumerProperties;
    private final ObjectMapper objectMapper;
    private final ReportJobService reportJobService;
    private final ReportJobProcessor reportJobProcessor;
    private final NotificationService notificationService;
    private volatile String queueUrl;

    public ReportJobQueueConsumer(
        SqsClient sqsClient,
        AwsProperties awsProperties,
        ReportJobConsumerProperties consumerProperties,
        ObjectMapper objectMapper,
        ReportJobService reportJobService,
        ReportJobProcessor reportJobProcessor,
        NotificationService notificationService
    ) {
        this.sqsClient = sqsClient;
        this.awsProperties = awsProperties;
        this.consumerProperties = consumerProperties;
        this.objectMapper = objectMapper;
        this.reportJobService = reportJobService;
        this.reportJobProcessor = reportJobProcessor;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${app.report-jobs.consumer.poll-delay-ms:1000}")
    public void pollQueue() {
        List<Message> messages = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .maxNumberOfMessages(consumerProperties.getMaxMessages())
                .waitTimeSeconds(consumerProperties.getWaitTimeSeconds())
                .build()
        ).messages();

        messages.forEach(this::handleMessage);
    }

    private void handleMessage(Message message) {
        try {
            ReportGenerationMessage reportMessage =
                objectMapper.readValue(message.body(), ReportGenerationMessage.class);

            log.info(
                "Starting report job processing for jobId={}, userId={}, reportType={}, reportFormat={}",
                reportMessage.reportJobId(),
                reportMessage.userId(),
                reportMessage.reportType(),
                reportMessage.reportFormat()
            );
            reportJobService.markProcessing(reportMessage.reportJobId());
            String s3Key = reportJobProcessor.process(reportJobService.getReportJobEntity(reportMessage.reportJobId()));
            reportJobService.markDone(reportMessage.reportJobId(), s3Key);
            notifySafely(reportJobService.getReportJobEntity(reportMessage.reportJobId()), true);
            log.info(
                "Report job processing completed successfully for jobId={}, reportType={}, reportFormat={}, s3Key={}",
                reportMessage.reportJobId(),
                reportMessage.reportType(),
                reportMessage.reportFormat(),
                s3Key
            );
        } catch (ResourceNotFoundException exception) {
            log.warn(
                "Skipping orphaned report job message because the job no longer exists: {}",
                exception.getMessage()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize report job message", exception);
        } catch (Exception exception) {
            tryMarkFailed(message, exception);
        } finally {
            deleteMessage(message);
        }
    }

    private void tryMarkFailed(Message message, Exception exception) {
        try {
            ReportGenerationMessage reportMessage =
                objectMapper.readValue(message.body(), ReportGenerationMessage.class);
            log.error(
                "Report job processing failed for jobId={}, userId={}, reportType={}, reportFormat={}: {}",
                reportMessage.reportJobId(),
                reportMessage.userId(),
                reportMessage.reportType(),
                reportMessage.reportFormat(),
                exception.getMessage(),
                exception
            );
            reportJobService.markFailed(reportMessage.reportJobId(), exception.getMessage());
            notifySafely(reportJobService.getReportJobEntity(reportMessage.reportJobId()), false);
        } catch (Exception ignored) {
            // If deserialization fails or the job no longer exists, the message is still deleted
            // to avoid blocking the queue with an unprocessable payload.
        }
    }

    private void notifySafely(com.blyndov.homebudgetreceiptsmanager.entity.ReportJob reportJob, boolean success) {
        try {
            if (success) {
                notificationService.sendReportReadyNotification(reportJob);
            } else {
                notificationService.sendReportFailedNotification(reportJob);
            }
        } catch (Exception exception) {
            log.error(
                "Notification delivery failed but report job state remains unchanged for jobId={}, status={}: {}",
                reportJob.getId(),
                reportJob.getStatus(),
                exception.getMessage(),
                exception
            );
        }
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .receiptHandle(message.receiptHandle())
                .build()
        );
    }

    private String resolveQueueUrl() {
        if (queueUrl == null) {
            synchronized (this) {
                if (queueUrl == null) {
                    queueUrl = sqsClient.getQueueUrl(
                        GetQueueUrlRequest.builder()
                            .queueName(awsProperties.getSqs().getQueueName())
                            .build()
                    ).queueUrl();
                }
            }
        }

        return queueUrl;
    }
}
