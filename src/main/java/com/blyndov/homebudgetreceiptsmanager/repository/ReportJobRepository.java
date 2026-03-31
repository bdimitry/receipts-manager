package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportJobRepository extends JpaRepository<ReportJob, Long> {

    List<ReportJob> findAllByUser_IdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<ReportJob> findByIdAndUser_Id(Long id, Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<ReportJob> findDetailedById(Long id);
}
