package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    private final ReportDataService reportDataService;
    private final List<ReportGenerator> reportGenerators;
    private final S3StorageService s3StorageService;

    public ReportGenerationService(
        ReportDataService reportDataService,
        List<ReportGenerator> reportGenerators,
        S3StorageService s3StorageService
    ) {
        this.reportDataService = reportDataService;
        this.reportGenerators = reportGenerators;
        this.s3StorageService = s3StorageService;
    }

    public String generateReport(ReportJob reportJob) {
        log.info(
            "Generating report for jobId={}, userId={}, year={}, month={}, reportType={}, reportFormat={}",
            reportJob.getId(),
            reportJob.getUser().getId(),
            reportJob.getYear(),
            reportJob.getMonth(),
            reportJob.getReportType(),
            reportJob.getReportFormat()
        );

        ReportDocument reportDocument = reportDataService.buildReportDocument(reportJob);
        GeneratedReportFile generatedReportFile = resolveGenerator(reportJob).generate(reportDocument);
        String s3Key = buildS3Key(reportJob, generatedReportFile.fileExtension());

        s3StorageService.upload(s3Key, generatedReportFile.contentType(), generatedReportFile.content());

        log.info(
            "Report uploaded for jobId={}, userId={}, reportType={}, reportFormat={}, s3Key={}",
            reportJob.getId(),
            reportJob.getUser().getId(),
            reportJob.getReportType(),
            reportJob.getReportFormat(),
            s3Key
        );

        return s3Key;
    }

    private ReportGenerator resolveGenerator(ReportJob reportJob) {
        return reportGenerators.stream()
            .filter(generator -> generator.supports(reportJob.getReportType(), reportJob.getReportFormat()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No report generator found for %s/%s".formatted(reportJob.getReportType(), reportJob.getReportFormat())
            ));
    }

    private String buildS3Key(ReportJob reportJob, String extension) {
        return "reports/%d/%s/%d-%02d-%s-%s.%s".formatted(
            reportJob.getUser().getId(),
            toSlug(reportJob.getReportType().name()),
            reportJob.getYear(),
            reportJob.getMonth(),
            reportJob.getReportFormat().name().toLowerCase(Locale.ROOT),
            UUID.randomUUID(),
            extension
        );
    }

    private String toSlug(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
