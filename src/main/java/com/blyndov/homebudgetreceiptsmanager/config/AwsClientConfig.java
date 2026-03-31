package com.blyndov.homebudgetreceiptsmanager.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientConfig {

    @Bean
    public S3Client s3Client(AwsProperties awsProperties) {
        return S3Client.builder()
            .region(Region.of(awsProperties.getRegion()))
            .endpointOverride(URI.create(awsProperties.getEndpoint()))
            .credentialsProvider(buildCredentialsProvider(awsProperties))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    }

    @Bean
    public S3Presigner s3Presigner(AwsProperties awsProperties) {
        return S3Presigner.builder()
            .region(Region.of(awsProperties.getRegion()))
            .endpointOverride(URI.create(awsProperties.getEndpoint()))
            .credentialsProvider(buildCredentialsProvider(awsProperties))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    }

    @Bean
    public SqsClient sqsClient(AwsProperties awsProperties) {
        return SqsClient.builder()
            .region(Region.of(awsProperties.getRegion()))
            .endpointOverride(URI.create(awsProperties.getEndpoint()))
            .credentialsProvider(buildCredentialsProvider(awsProperties))
            .build();
    }

    private StaticCredentialsProvider buildCredentialsProvider(AwsProperties awsProperties) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                awsProperties.getCredentials().getAccessKey(),
                awsProperties.getCredentials().getSecretKey()
            )
        );
    }
}
