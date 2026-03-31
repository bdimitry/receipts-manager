package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {

    List<Receipt> findAllByUser_IdOrderByUploadedAtDescIdDesc(Long userId);

    @EntityGraph(attributePaths = {"user", "purchase", "lineItems"})
    Optional<Receipt> findDetailedById(Long id);

    Optional<Receipt> findByIdAndUser_Id(Long id, Long userId);
}
