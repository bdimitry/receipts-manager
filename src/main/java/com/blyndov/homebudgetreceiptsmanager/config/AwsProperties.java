package com.blyndov.homebudgetreceiptsmanager.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {

    @NotBlank
    private String region;

    @NotBlank
    private String endpoint;

    @Valid
    private Credentials credentials = new Credentials();

    @Valid
    private S3 s3 = new S3();

    @Valid
    private Sqs sqs = new Sqs();

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3;
    }

    public Sqs getSqs() {
        return sqs;
    }

    public void setSqs(Sqs sqs) {
        this.sqs = sqs;
    }

    public static class Credentials {

        @NotBlank
        private String accessKey;

        @NotBlank
        private String secretKey;

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    public static class S3 {

        @NotBlank
        private String bucketName;

        public String getBucketName() {
            return bucketName;
        }

        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }
    }

    public static class Sqs {

        @NotBlank
        private String queueName;

        @NotBlank
        private String receiptOcrQueueName;

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public String getReceiptOcrQueueName() {
            return receiptOcrQueueName;
        }

        public void setReceiptOcrQueueName(String receiptOcrQueueName) {
            this.receiptOcrQueueName = receiptOcrQueueName;
        }
    }
}
