package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJobStatus;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import java.time.Instant;

public record ReportDownloadResponse(
    Long reportJobId,
    ReportType reportType,
    ReportFormat reportFormat,
    ReportJobStatus status,
    String fileName,
    String contentType,
    String downloadUrl,
    Instant expiresAt
) {
}
