package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
public class ReceiptOcrQueueProducer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptOcrQueueProducer.class);

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    private final ObjectMapper objectMapper;
    private volatile String queueUrl;

    public ReceiptOcrQueueProducer(
        SqsClient sqsClient,
        AwsProperties awsProperties,
        ObjectMapper objectMapper
    ) {
        this.sqsClient = sqsClient;
        this.awsProperties = awsProperties;
        this.objectMapper = objectMapper;
    }

    public void publish(ReceiptOcrMessage message) {
        try {
            sqsClient.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(resolveQueueUrl())
                    .messageBody(objectMapper.writeValueAsString(message))
                    .build()
            );
            log.info(
                "Receipt OCR message published to SQS for receiptId={}, userId={}",
                message.receiptId(),
                message.userId()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize receipt OCR message", exception);
        }
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
