package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.Purchase;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PurchaseRepository extends JpaRepository<Purchase, Long>, JpaSpecificationExecutor<Purchase> {

    Optional<Purchase> findByIdAndUser_Id(Long id, Long userId);

    List<Purchase> findAllByUser_IdAndPurchaseDateGreaterThanEqualAndPurchaseDateLessThanOrderByPurchaseDateAscIdAsc(
        Long userId,
        LocalDate startDateInclusive,
        LocalDate endDateExclusive
    );
}
