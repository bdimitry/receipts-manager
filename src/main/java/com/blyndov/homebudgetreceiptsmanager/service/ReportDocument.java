package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.List;

public record ReportDocument(
    String title,
    List<ReportMetadataItem> metadata,
    List<ReportSection> sections
) {
}
