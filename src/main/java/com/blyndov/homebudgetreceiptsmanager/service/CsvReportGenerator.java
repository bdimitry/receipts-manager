package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CsvReportGenerator implements ReportGenerator {

    @Override
    public boolean supports(ReportType reportType, ReportFormat reportFormat) {
        return reportFormat == ReportFormat.CSV;
    }

    @Override
    public GeneratedReportFile generate(ReportDocument reportDocument) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, reportDocument.title());
        reportDocument.metadata().forEach(item -> appendCsvLine(builder, item.label(), item.value()));
        appendLine(builder);
        reportDocument.sections().forEach(section -> appendSection(builder, section));

        return new GeneratedReportFile(
            builder.toString().getBytes(StandardCharsets.UTF_8),
            ReportFormat.CSV.getContentType(),
            ReportFormat.CSV.getFileExtension()
        );
    }

    private void appendSection(StringBuilder builder, ReportSection section) {
        if (StringUtils.hasText(section.title())) {
            appendLine(builder, section.title());
        }

        if (section.rows().isEmpty() && StringUtils.hasText(section.emptyMessage())) {
            appendLine(builder, section.emptyMessage());
        } else {
            if (section.renderHeaders() && !section.headers().isEmpty()) {
                appendCsvLine(builder, section.headers().toArray(String[]::new));
            }
            section.rows().forEach(row -> appendCsvLine(builder, row.toArray(String[]::new)));
        }

        appendLine(builder);
    }

    private void appendCsvLine(StringBuilder builder, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(toCsvValue(values[index]));
        }
        builder.append('\n');
    }

    private void appendLine(StringBuilder builder) {
        builder.append('\n');
    }

    private void appendLine(StringBuilder builder, String value) {
        builder.append(value).append('\n');
    }

    private String toCsvValue(String value) {
        if (value == null) {
            return "";
        }

        boolean shouldQuote = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!shouldQuote) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
