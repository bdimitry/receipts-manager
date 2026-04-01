package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.ReportGenerationMessage;
import com.blyndov.homebudgetreceiptsmanager.client.ReportJobQueueProducer;
import com.blyndov.homebudgetreceiptsmanager.config.ReportDownloadProperties;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportDownloadResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportFileContent;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.exception.ReportJobStateException;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class ReportJobService {

    private static final Logger log = LoggerFactory.getLogger(ReportJobService.class);

    private final ReportJobRepository reportJobRepository;
    private final AuthService authService;
    private final ReportJobQueueProducer reportJobQueueProducer;
    private final S3StorageService s3StorageService;
    private final ReportDownloadProperties reportDownloadProperties;

    public ReportJobService(
        ReportJobRepository reportJobRepository,
        AuthService authService,
        ReportJobQueueProducer reportJobQueueProducer,
        S3StorageService s3StorageService,
        ReportDownloadProperties reportDownloadProperties
    ) {
        this.reportJobRepository = reportJobRepository;
        this.authService = authService;
        this.reportJobQueueProducer = reportJobQueueProducer;
        this.s3StorageService = s3StorageService;
        this.reportDownloadProperties = reportDownloadProperties;
    }

    @Transactional
    public ReportJobResponse createReport(CreateReportRequest request) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        log.info(
            "Creating report job for userId={}, year={}, month={}, reportType={}, reportFormat={}",
            currentUser.getId(),
            request.year(),
            request.month(),
            request.reportType(),
            request.reportFormat()
        );

        ReportJob reportJob = new ReportJob();
        reportJob.setUser(currentUser);
        reportJob.setYear(request.year());
        reportJob.setMonth(request.month());
        reportJob.setReportType(request.reportType());
        reportJob.setReportFormat(request.reportFormat());
        reportJob.setStatus(ReportJobStatus.NEW);

        ReportJob savedJob = reportJobRepository.saveAndFlush(reportJob);
        ReportGenerationMessage reportMessage = new ReportGenerationMessage(
            savedJob.getId(),
            currentUser.getId(),
            savedJob.getYear(),
            savedJob.getMonth(),
            savedJob.getReportType(),
            savedJob.getReportFormat()
        );
        publishAfterCommit(reportMessage);

        log.info(
            "Report job created and queued: jobId={}, userId={}, reportType={}, reportFormat={}",
            savedJob.getId(),
            currentUser.getId(),
            savedJob.getReportType(),
            savedJob.getReportFormat()
        );
        return mapToResponse(savedJob);
    }

    @Transactional
    public ReportJobResponse createMonthlyReport(CreateMonthlyReportRequest request) {
        return createReport(
            new CreateReportRequest(
                request.year(),
                request.month(),
                ReportType.MONTHLY_SPENDING,
                ReportFormat.CSV
            )
        );
    }

    @Transactional(readOnly = true)
    public List<ReportJobResponse> getReportJobs() {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return reportJobRepository.findAllByUser_IdOrderByCreatedAtDescIdDesc(currentUser.getId())
            .stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public ReportJobResponse getReportJob(Long id) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return reportJobRepository.findByIdAndUser_Id(id, currentUser.getId())
            .map(this::mapToResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Report job not found"));
    }

    @Transactional(readOnly = true)
    public ReportJob getReportJobEntity(Long id) {
        return reportJobRepository.findDetailedById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Report job not found"));
    }

    @Transactional(readOnly = true)
    public ReportDownloadResponse getDownload(Long id) {
        ReportJob reportJob = getReadyOwnedReportJob(id);

        log.info(
            "Generated report download contract for jobId={}, userId={}, reportType={}, reportFormat={}, s3Key={}",
            reportJob.getId(),
            reportJob.getUser().getId(),
            reportJob.getReportType(),
            reportJob.getReportFormat(),
            reportJob.getS3Key()
        );

        return new ReportDownloadResponse(
            reportJob.getId(),
            reportJob.getReportType(),
            reportJob.getReportFormat(),
            reportJob.getStatus(),
            buildFileName(reportJob),
            reportJob.getReportFormat().getContentType(),
            "/api/reports/" + reportJob.getId() + "/file",
            Instant.now().plus(reportDownloadProperties.getExpiration())
        );
    }

    @Transactional(readOnly = true)
    public ReportFileContent downloadFile(Long id) {
        ReportJob reportJob = getReadyOwnedReportJob(id);

        return buildReadyFileContent(reportJob);
    }

    @Transactional(readOnly = true)
    public ReportFileContent buildReadyFileContent(ReportJob reportJob) {
        validateReadyReportJob(reportJob);

        return new ReportFileContent(
            buildFileName(reportJob),
            reportJob.getReportFormat().getContentType(),
            s3StorageService.download(reportJob.getS3Key())
        );
    }

    @Transactional
    public void markProcessing(Long id) {
        ReportJob reportJob = getReportJobEntity(id);
        reportJob.setStatus(ReportJobStatus.PROCESSING);
        reportJob.setS3Key(null);
        reportJob.setErrorMessage(null);
        reportJobRepository.save(reportJob);
    }

    @Transactional
    public void markDone(Long id, String s3Key) {
        ReportJob reportJob = getReportJobEntity(id);
        reportJob.setStatus(ReportJobStatus.DONE);
        reportJob.setS3Key(s3Key);
        reportJob.setErrorMessage(null);
        reportJobRepository.save(reportJob);
    }

    @Transactional
    public void markFailed(Long id, String errorMessage) {
        ReportJob reportJob = getReportJobEntity(id);
        reportJob.setStatus(ReportJobStatus.FAILED);
        reportJob.setS3Key(null);
        reportJob.setErrorMessage(errorMessage);
        reportJobRepository.save(reportJob);
    }

    @Transactional(readOnly = true)
    public ReportJob getOwnedReportJobEntity(Long id) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return reportJobRepository.findByIdAndUser_Id(id, currentUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Report job not found"));
    }

    private ReportJob getReadyOwnedReportJob(Long id) {
        ReportJob reportJob = getOwnedReportJobEntity(id);
        validateReadyReportJob(reportJob);
        return reportJob;
    }

    private void validateReadyReportJob(ReportJob reportJob) {
        if (reportJob.getStatus() == ReportJobStatus.FAILED) {
            throw new ReportJobStateException(buildFailedMessage(reportJob));
        }

        if (reportJob.getStatus() != ReportJobStatus.DONE || !StringUtils.hasText(reportJob.getS3Key())) {
            throw new ReportJobStateException("Report is not ready for download yet");
        }
    }

    private ReportJobResponse mapToResponse(ReportJob reportJob) {
        return new ReportJobResponse(
            reportJob.getId(),
            reportJob.getYear(),
            reportJob.getMonth(),
            reportJob.getReportType(),
            reportJob.getReportFormat(),
            reportJob.getStatus(),
            reportJob.getS3Key(),
            reportJob.getErrorMessage(),
            reportJob.getCreatedAt(),
            reportJob.getUpdatedAt()
        );
    }

    private String buildFileName(ReportJob reportJob) {
        return "%s-%d-%02d.%s".formatted(
            toSlug(reportJob.getReportType().name()),
            reportJob.getYear(),
            reportJob.getMonth(),
            reportJob.getReportFormat().getFileExtension()
        );
    }

    private String buildFailedMessage(ReportJob reportJob) {
        if (StringUtils.hasText(reportJob.getErrorMessage())) {
            return "Report generation failed: " + reportJob.getErrorMessage();
        }
        return "Report generation failed";
    }

    private String toSlug(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private void publishAfterCommit(ReportGenerationMessage reportMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reportJobQueueProducer.publish(reportMessage);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reportJobQueueProducer.publish(reportMessage);
            }
        });
    }
}
