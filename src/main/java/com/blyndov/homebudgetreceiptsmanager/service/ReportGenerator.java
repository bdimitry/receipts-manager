package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;

public interface ReportGenerator {

    boolean supports(ReportType reportType, ReportFormat reportFormat);

    GeneratedReportFile generate(ReportDocument reportDocument);
}
