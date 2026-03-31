package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.service.ReceiptOcrParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ReceiptOcrParserTests {

    private final ReceiptOcrParser parser = new ReceiptOcrParser();

    @Test
    void parserExtractsStoreTotalDateAndMultipleLineItemsFromNoisyCyrillicReceipt() throws IOException {
        String rawText = new ClassPathResource("fixtures/ocr/receipt-cyrillic-noisy.txt")
            .getContentAsString(StandardCharsets.UTF_8);

        ReceiptOcrParser.ParsedReceiptData parsed = parser.parse(rawText);

        assertThat(parsed.parsedStoreName()).isEqualTo("СІЛЬПО");
        assertThat(parsed.parsedTotalAmount()).isEqualByComparingTo(new BigDecimal("212.31"));
        assertThat(parsed.parsedPurchaseDate()).isEqualTo(LocalDate.of(2026, 3, 14));
        assertThat(parsed.lineItems()).hasSizeGreaterThan(1);

        assertThat(parsed.lineItems())
            .extracting(ReceiptOcrParser.ParsedReceiptLineItem::title)
            .contains("МОЛОКО ЯГОТИНСЬКЕ", "ХЛІБ БОРОДИНСЬКИЙ", "БАНАНИ 1.245кг", "Пакет");

        assertThat(parsed.lineItems().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(parsed.lineItems().get(0).unitPrice()).isEqualByComparingTo("42.50");
        assertThat(parsed.lineItems().get(0).lineTotal()).isEqualByComparingTo("85.00");

        assertThat(parsed.lineItems().get(2).quantity()).isEqualByComparingTo("1.245");
        assertThat(parsed.lineItems().get(2).unit()).isEqualTo("кг");
        assertThat(parsed.lineItems().get(2).lineTotal()).isEqualByComparingTo("84.41");

        assertThat(parsed.lineItems().get(3).title()).isEqualTo("Пакет");
        assertThat(parsed.lineItems().get(3).lineTotal()).isEqualByComparingTo("3.00");
        assertThat(parsed.lineItems())
            .extracting(ReceiptOcrParser.ParsedReceiptLineItem::rawFragment)
            .allMatch(value -> value != null && !value.isBlank());
    }

    @Test
    void parserExtractsItemWhenTitleAndAmountAreOnSeparateLines() {
        String rawText = """
            СІЛЬПО
            Дата 14.03.2026 18:42
            ЙОГУРТ 2%
            35.50
            СУМА 35.50
            """;

        ReceiptOcrParser.ParsedReceiptData parsed = parser.parse(rawText);

        assertThat(parsed.lineItems()).hasSize(1);
        assertThat(parsed.lineItems().getFirst().title()).isEqualTo("ЙОГУРТ 2%");
        assertThat(parsed.lineItems().getFirst().lineTotal()).isEqualByComparingTo("35.50");
        assertThat(parsed.lineItems().getFirst().rawFragment()).contains("ЙОГУРТ 2%", "35.50");
    }
}
