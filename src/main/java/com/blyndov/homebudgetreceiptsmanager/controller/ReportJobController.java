package com.blyndov.homebudgetreceiptsmanager.controller;

import com.blyndov.homebudgetreceiptsmanager.dto.CreateReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.CreateMonthlyReportRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportDownloadResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReportJobResponse;
import com.blyndov.homebudgetreceiptsmanager.service.ReportJobService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportJobController {

    private final ReportJobService reportJobService;

    public ReportJobController(ReportJobService reportJobService) {
        this.reportJobService = reportJobService;
    }

    @PostMapping
    public ResponseEntity<ReportJobResponse> createReport(
        @Valid @RequestBody CreateReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reportJobService.createReport(request));
    }

    @PostMapping("/monthly")
    public ResponseEntity<ReportJobResponse> createMonthlyReport(
        @Valid @RequestBody CreateMonthlyReportRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reportJobService.createMonthlyReport(request));
    }

    @GetMapping
    public List<ReportJobResponse> getReportJobs() {
        return reportJobService.getReportJobs();
    }

    @GetMapping("/{id}")
    public ReportJobResponse getReportJob(@PathVariable Long id) {
        return reportJobService.getReportJob(id);
    }

    @GetMapping("/{id}/download")
    public ReportDownloadResponse downloadReport(@PathVariable Long id) {
        return reportJobService.getDownload(id);
    }
}
