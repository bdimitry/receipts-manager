package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.client.ReceiptOcrMessage;
import com.blyndov.homebudgetreceiptsmanager.client.ReceiptOcrQueueProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReceiptOcrDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(ReceiptOcrDispatchListener.class);

    private final ReceiptOcrQueueProducer receiptOcrQueueProducer;
    private final ReceiptOcrService receiptOcrService;

    public ReceiptOcrDispatchListener(
        ReceiptOcrQueueProducer receiptOcrQueueProducer,
        ReceiptOcrService receiptOcrService
    ) {
        this.receiptOcrQueueProducer = receiptOcrQueueProducer;
        this.receiptOcrService = receiptOcrService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceiptUploaded(ReceiptUploadedEvent event) {
        try {
            receiptOcrQueueProducer.publish(new ReceiptOcrMessage(event.receiptId(), event.userId()));
            log.info("Queued OCR processing for receiptId={}, userId={}", event.receiptId(), event.userId());
        } catch (Exception exception) {
            receiptOcrService.markEnqueueFailed(event.receiptId(), "Failed to enqueue OCR processing");
        }
    }
}
