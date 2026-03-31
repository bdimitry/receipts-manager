package com.blyndov.homebudgetreceiptsmanager.service;

import java.util.List;

public record ReportSection(
    String title,
    List<String> headers,
    List<List<String>> rows,
    String emptyMessage,
    boolean renderHeaders
) {
}
