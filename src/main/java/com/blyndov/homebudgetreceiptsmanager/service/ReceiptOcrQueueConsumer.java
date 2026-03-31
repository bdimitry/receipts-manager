package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.ReceiptOcrMessage;
import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.config.ReceiptOcrConsumerProperties;
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
@ConditionalOnProperty(name = "app.receipts.ocr.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptOcrQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptOcrQueueConsumer.class);

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    private final ReceiptOcrConsumerProperties consumerProperties;
    private final ObjectMapper objectMapper;
    private final ReceiptOcrService receiptOcrService;
    private volatile String queueUrl;

    public ReceiptOcrQueueConsumer(
        SqsClient sqsClient,
        AwsProperties awsProperties,
        ReceiptOcrConsumerProperties consumerProperties,
        ObjectMapper objectMapper,
        ReceiptOcrService receiptOcrService
    ) {
        this.sqsClient = sqsClient;
        this.awsProperties = awsProperties;
        this.consumerProperties = consumerProperties;
        this.objectMapper = objectMapper;
        this.receiptOcrService = receiptOcrService;
    }

    @Scheduled(fixedDelayString = "${app.receipts.ocr.consumer.poll-delay-ms:1000}")
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
            ReceiptOcrMessage receiptMessage = objectMapper.readValue(message.body(), ReceiptOcrMessage.class);

            log.info(
                "Starting receipt OCR processing for receiptId={}, userId={}",
                receiptMessage.receiptId(),
                receiptMessage.userId()
            );
            receiptOcrService.markProcessing(receiptMessage.receiptId());
            receiptOcrService.process(receiptMessage.receiptId());
        } catch (ResourceNotFoundException exception) {
            log.warn("Skipping orphaned receipt OCR message because the receipt no longer exists: {}", exception.getMessage());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize receipt OCR message", exception);
        } catch (Exception exception) {
            tryMarkFailed(message, exception);
        } finally {
            deleteMessage(message);
        }
    }

    private void tryMarkFailed(Message message, Exception exception) {
        try {
            ReceiptOcrMessage receiptMessage = objectMapper.readValue(message.body(), ReceiptOcrMessage.class);
            log.error(
                "Receipt OCR processing failed for receiptId={}, userId={}: {}",
                receiptMessage.receiptId(),
                receiptMessage.userId(),
                exception.getMessage(),
                exception
            );
            receiptOcrService.markFailed(receiptMessage.receiptId(), exception.getMessage());
        } catch (Exception ignored) {
            // Unprocessable messages are deleted to prevent queue blocking.
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
                            .queueName(awsProperties.getSqs().getReceiptOcrQueueName())
                            .build()
                    ).queueUrl();
                }
            }
        }

        return queueUrl;
    }
}
