package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptCorrection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptCorrectionRepository extends JpaRepository<ReceiptCorrection, Long> {

    Optional<ReceiptCorrection> findFirstByReceipt_IdOrderByCreatedAtDescIdDesc(Long receiptId);
}
