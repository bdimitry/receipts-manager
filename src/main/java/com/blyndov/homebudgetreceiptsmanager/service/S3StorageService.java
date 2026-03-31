package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.config.AwsProperties;
import com.blyndov.homebudgetreceiptsmanager.exception.FileStorageException;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@Service
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AwsProperties awsProperties;

    public S3StorageService(S3Client s3Client, S3Presigner s3Presigner, AwsProperties awsProperties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.awsProperties = awsProperties;
    }

    public void upload(String s3Key, MultipartFile file) {
        try {
            log.info(
                "Uploading object to S3 bucket={}, key={}, contentType={}, size={}",
                awsProperties.getS3().getBucketName(),
                s3Key,
                file.getContentType(),
                file.getSize()
            );
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException exception) {
            throw new FileStorageException("Failed to read uploaded file", exception);
        } catch (RuntimeException exception) {
            throw new FileStorageException("Failed to upload file to S3", exception);
        }
    }

    public void upload(String s3Key, String contentType, byte[] content) {
        try {
            log.info(
                "Uploading object to S3 bucket={}, key={}, contentType={}, size={}",
                awsProperties.getS3().getBucketName(),
                s3Key,
                contentType,
                content.length
            );
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .build(),
                RequestBody.fromBytes(content)
            );
        } catch (RuntimeException exception) {
            throw new FileStorageException("Failed to upload file to S3", exception);
        }
    }

    public void delete(String s3Key) {
        log.info("Deleting object from S3 bucket={}, key={}", awsProperties.getS3().getBucketName(), s3Key);
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(awsProperties.getS3().getBucketName())
                .key(s3Key)
                .build()
        );
    }

    public byte[] download(String s3Key) {
        try {
            log.info("Downloading object from S3 bucket={}, key={}", awsProperties.getS3().getBucketName(), s3Key);
            return s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket(awsProperties.getS3().getBucketName())
                    .key(s3Key)
                    .build()
            ).asByteArray();
        } catch (RuntimeException exception) {
            throw new FileStorageException("Failed to download file from S3", exception);
        }
    }

    public PresignedGetObjectRequest generateDownloadRequest(String s3Key, Duration expiration) {
        try {
            log.info(
                "Generating presigned S3 download URL for bucket={}, key={}, expiration={}",
                awsProperties.getS3().getBucketName(),
                s3Key,
                expiration
            );
            return s3Presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(expiration)
                    .getObjectRequest(
                        GetObjectRequest.builder()
                            .bucket(awsProperties.getS3().getBucketName())
                            .key(s3Key)
                            .build()
                    )
                    .build()
            );
        } catch (RuntimeException exception) {
            throw new FileStorageException("Failed to generate report download URL", exception);
        }
    }
}
