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
public class ReportJobQueueProducer {

    private static final Logger log = LoggerFactory.getLogger(ReportJobQueueProducer.class);

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    private final ObjectMapper objectMapper;
    private volatile String queueUrl;

    public ReportJobQueueProducer(
        SqsClient sqsClient,
        AwsProperties awsProperties,
        ObjectMapper objectMapper
    ) {
        this.sqsClient = sqsClient;
        this.awsProperties = awsProperties;
        this.objectMapper = objectMapper;
    }

    public void publish(ReportGenerationMessage message) {
        try {
            sqsClient.sendMessage(
                SendMessageRequest.builder()
                    .queueUrl(resolveQueueUrl())
                    .messageBody(objectMapper.writeValueAsString(message))
                    .build()
            );
            log.info(
                "Report job message published to SQS for jobId={}, userId={}, year={}, month={}, reportType={}, reportFormat={}",
                message.reportJobId(),
                message.userId(),
                message.year(),
                message.month(),
                message.reportType(),
                message.reportFormat()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize report job message", exception);
        }
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
