package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class XlsxReportGenerator implements ReportGenerator {

    @Override
    public boolean supports(ReportType reportType, ReportFormat reportFormat) {
        return reportFormat == ReportFormat.XLSX;
    }

    @Override
    public GeneratedReportFile generate(ReportDocument reportDocument) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Report");
            CellStyle titleStyle = boldStyle(workbook, (short) 14);
            CellStyle sectionStyle = boldStyle(workbook, (short) 11);
            CellStyle headerStyle = boldStyle(workbook, (short) 10);

            int rowIndex = 0;
            rowIndex = writeCell(sheet, rowIndex, 0, reportDocument.title(), titleStyle);
            rowIndex++;

            for (ReportMetadataItem metadataItem : reportDocument.metadata()) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(metadataItem.label());
                row.createCell(1).setCellValue(metadataItem.value());
            }
            rowIndex++;

            for (ReportSection section : reportDocument.sections()) {
                if (StringUtils.hasText(section.title())) {
                    rowIndex = writeCell(sheet, rowIndex, 0, section.title(), sectionStyle);
                }

                if (section.rows().isEmpty() && StringUtils.hasText(section.emptyMessage())) {
                    rowIndex = writeCell(sheet, rowIndex, 0, section.emptyMessage(), null);
                } else {
                    if (section.renderHeaders() && !section.headers().isEmpty()) {
                        org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(rowIndex++);
                        for (int index = 0; index < section.headers().size(); index++) {
                            Cell cell = headerRow.createCell(index);
                            cell.setCellValue(section.headers().get(index));
                            cell.setCellStyle(headerStyle);
                        }
                    }

                    for (var values : section.rows()) {
                        org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex++);
                        for (int index = 0; index < values.size(); index++) {
                            row.createCell(index).setCellValue(values.get(index));
                        }
                    }
                }
                rowIndex++;
            }

            int width = maxColumnCount(reportDocument);
            for (int index = 0; index < width; index++) {
                sheet.autoSizeColumn(index);
            }

            workbook.write(outputStream);
            return new GeneratedReportFile(
                outputStream.toByteArray(),
                ReportFormat.XLSX.getContentType(),
                ReportFormat.XLSX.getFileExtension()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to generate XLSX report", exception);
        }
    }

    private CellStyle boldStyle(XSSFWorkbook workbook, short height) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints(height);

        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private int writeCell(XSSFSheet sheet, int rowIndex, int cellIndex, String value, CellStyle style) {
        org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(cellIndex);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return rowIndex + 1;
    }

    private int maxColumnCount(ReportDocument reportDocument) {
        int max = 2;
        for (ReportSection section : reportDocument.sections()) {
            max = Math.max(max, section.headers().size());
            for (var row : section.rows()) {
                max = Math.max(max, row.size());
            }
        }
        return max;
    }
}
