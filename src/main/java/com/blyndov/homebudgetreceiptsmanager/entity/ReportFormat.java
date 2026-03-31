package com.blyndov.homebudgetreceiptsmanager.entity;

public enum ReportFormat {
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
    XLSX(
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final String fileExtension;
    private final String contentType;

    ReportFormat(String fileExtension, String contentType) {
        this.fileExtension = fileExtension;
        this.contentType = contentType;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getContentType() {
        return contentType;
    }
}
