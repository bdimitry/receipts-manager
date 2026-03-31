package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import java.time.Instant;

public record ReportJobResponse(
    Long id,
    Integer year,
    Integer month,
    ReportType reportType,
    ReportFormat reportFormat,
    ReportJobStatus status,
    String s3Key,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt
) {
}
