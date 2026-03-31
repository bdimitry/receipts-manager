package com.blyndov.homebudgetreceiptsmanager.client;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;

public record ReportGenerationMessage(
    Long reportJobId,
    Long userId,
    Integer year,
    Integer month,
    ReportType reportType,
    ReportFormat reportFormat
) {
}
