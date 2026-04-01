package com.blyndov.homebudgetreceiptsmanager.dto;

public record ReportFileContent(
    String fileName,
    String contentType,
    byte[] content
) {
}
