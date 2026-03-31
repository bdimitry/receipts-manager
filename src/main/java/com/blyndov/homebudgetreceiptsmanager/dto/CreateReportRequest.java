package com.blyndov.homebudgetreceiptsmanager.dto;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateReportRequest(
    @NotNull @Min(2000) @Max(9999) Integer year,
    @NotNull @Min(1) @Max(12) Integer month,
    @NotNull ReportType reportType,
    @NotNull ReportFormat reportFormat
) {
}
