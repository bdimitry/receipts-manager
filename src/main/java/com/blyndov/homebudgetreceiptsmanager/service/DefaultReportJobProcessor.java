package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import org.springframework.stereotype.Service;

@Service
public class DefaultReportJobProcessor implements ReportJobProcessor {

    private final ReportGenerationService reportGenerationService;

    public DefaultReportJobProcessor(ReportGenerationService reportGenerationService) {
        this.reportGenerationService = reportGenerationService;
    }

    @Override
    public String process(ReportJob reportJob) {
        return reportGenerationService.generateReport(reportJob);
    }
}
