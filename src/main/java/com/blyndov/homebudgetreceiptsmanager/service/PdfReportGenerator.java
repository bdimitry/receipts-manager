package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportFormat;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PdfReportGenerator implements ReportGenerator {

    private static final float MARGIN = 50f;
    private static final float BODY_FONT_SIZE = 10f;
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float SECTION_FONT_SIZE = 12f;
    private static final float LINE_HEIGHT = 15f;

    @Override
    public boolean supports(ReportType reportType, ReportFormat reportFormat) {
        return reportFormat == ReportFormat.PDF;
    }

    @Override
    public GeneratedReportFile generate(ReportDocument reportDocument) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            List<Line> lines = flatten(reportDocument);
            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font sectionFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream contentStream = new PDPageContentStream(document, page);
            float y = page.getMediaBox().getHeight() - MARGIN;

            for (Line line : lines) {
                PDType1Font font = switch (line.style()) {
                    case TITLE -> titleFont;
                    case SECTION -> sectionFont;
                    default -> bodyFont;
                };
                float fontSize = switch (line.style()) {
                    case TITLE -> TITLE_FONT_SIZE;
                    case SECTION -> SECTION_FONT_SIZE;
                    default -> BODY_FONT_SIZE;
                };

                if (y < MARGIN) {
                    contentStream.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    contentStream = new PDPageContentStream(document, page);
                    y = page.getMediaBox().getHeight() - MARGIN;
                }

                contentStream.beginText();
                contentStream.setFont(font, fontSize);
                contentStream.newLineAtOffset(MARGIN, y);
                contentStream.showText(line.value());
                contentStream.endText();
                y -= LINE_HEIGHT;
            }

            contentStream.close();
            document.save(outputStream);
            return new GeneratedReportFile(
                outputStream.toByteArray(),
                ReportFormat.PDF.getContentType(),
                ReportFormat.PDF.getFileExtension()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to generate PDF report", exception);
        }
    }

    private List<Line> flatten(ReportDocument reportDocument) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(reportDocument.title(), LineStyle.TITLE));

        reportDocument.metadata().forEach(item -> lines.add(new Line(item.label() + ": " + item.value(), LineStyle.BODY)));
        lines.add(new Line("", LineStyle.BODY));

        reportDocument.sections().forEach(section -> {
            if (StringUtils.hasText(section.title())) {
                lines.add(new Line(section.title(), LineStyle.SECTION));
            }

            if (section.rows().isEmpty() && StringUtils.hasText(section.emptyMessage())) {
                lines.add(new Line(section.emptyMessage(), LineStyle.BODY));
            } else {
                if (section.renderHeaders() && !section.headers().isEmpty()) {
                    lines.add(new Line(String.join(" | ", section.headers()), LineStyle.BODY));
                }
                section.rows().forEach(row -> lines.add(new Line(String.join(" | ", row), LineStyle.BODY)));
            }
            lines.add(new Line("", LineStyle.BODY));
        });

        return lines;
    }

    private record Line(String value, LineStyle style) {
    }

    private enum LineStyle {
        TITLE,
        SECTION,
        BODY
    }
}
