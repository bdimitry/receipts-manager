package com.blyndov.homebudgetreceiptsmanager.controller;

import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptOcrResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.ReceiptResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.CurrencyCode;
import com.blyndov.homebudgetreceiptsmanager.service.ReceiptService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping("/upload")
    public ResponseEntity<ReceiptResponse> uploadReceipt(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) Long purchaseId,
        @RequestParam CurrencyCode currency
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.uploadReceipt(file, purchaseId, currency));
    }

    @GetMapping
    public List<ReceiptResponse> getReceipts() {
        return receiptService.getReceipts();
    }

    @GetMapping("/{id}")
    public ReceiptResponse getReceipt(@PathVariable Long id) {
        return receiptService.getReceipt(id);
    }

    @GetMapping("/{id}/ocr")
    public ReceiptOcrResponse getReceiptOcr(@PathVariable Long id) {
        return receiptService.getReceiptOcr(id);
    }
}
