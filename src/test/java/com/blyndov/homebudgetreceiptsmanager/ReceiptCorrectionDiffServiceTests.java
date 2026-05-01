package com.blyndov.homebudgetreceiptsmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionItemRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptCorrectionRequest;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptCorrectionDiffService;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptCorrectionLineItemSnapshot;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptCorrectionSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptCorrectionDiffServiceTests {

    private final ReceiptCorrectionDiffService correctionDiffService = new ReceiptCorrectionDiffService();

    @Test
    void correctionSnapshotKeepsParsedValuesAndBuildsCorrectedValuesSeparately() {
        ReceiptCorrectionSnapshot parsedSnapshot = new ReceiptCorrectionSnapshot(
            "OCR STORE",
            LocalDate.of(2026, 4, 12),
            new BigDecimal("12.04"),
            CurrencyCode.UAH,
            List.of(new ReceiptCorrectionLineItemSnapshot("Payment fragment", null, null, null, new BigDecimal("12.04")))
        );

        ReceiptCorrectionRequest request = new ReceiptCorrectionRequest(
            "NOVUS",
            LocalDate.of(2026, 4, 12),
            new BigDecimal("103.98"),
            CurrencyCode.UAH,
            List.of(new ReceiptCorrectionItemRequest("Coca-Cola", null, null, null, new BigDecimal("49.99"))),
            false
        );

        ReceiptCorrectionSnapshot correctedSnapshot = correctionDiffService.correctedSnapshot(parsedSnapshot, request);

        assertThat(parsedSnapshot.storeName()).isEqualTo("OCR STORE");
        assertThat(parsedSnapshot.totalAmount()).isEqualByComparingTo("12.04");
        assertThat(correctedSnapshot.storeName()).isEqualTo("NOVUS");
        assertThat(correctedSnapshot.totalAmount()).isEqualByComparingTo("103.98");
        assertThat(correctedSnapshot.items()).extracting(ReceiptCorrectionLineItemSnapshot::title).containsExactly("Coca-Cola");
    }

    @Test
    void diffListsOnlyChangedFields() {
        ReceiptCorrectionSnapshot parsedSnapshot = new ReceiptCorrectionSnapshot(
            "NOVUS",
            LocalDate.of(2026, 4, 12),
            new BigDecimal("12.04"),
            CurrencyCode.UAH,
            List.of()
        );
        ReceiptCorrectionSnapshot correctedSnapshot = new ReceiptCorrectionSnapshot(
            "NOVUS",
            LocalDate.of(2026, 4, 12),
            new BigDecimal("103.98"),
            CurrencyCode.UAH,
            List.of()
        );

        assertThat(correctionDiffService.diff(parsedSnapshot, correctedSnapshot))
            .singleElement()
            .satisfies(diff -> {
                assertThat(diff.field()).isEqualTo("totalAmount");
                assertThat(diff.parsedValue()).isEqualTo("12.04");
                assertThat(diff.correctedValue()).isEqualTo("103.98");
            });
    }
}
