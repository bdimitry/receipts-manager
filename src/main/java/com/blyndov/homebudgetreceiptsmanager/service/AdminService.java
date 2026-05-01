package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.AdminOverviewResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.AdminUserResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReceiptRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.ReportJobRepository;
import com.blyndov.homebudgetreceiptsmanager.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminService {

    private final UserRepository userRepository;
    private final PurchaseRepository purchaseRepository;
    private final ReceiptRepository receiptRepository;
    private final ReportJobRepository reportJobRepository;

    public AdminService(
        UserRepository userRepository,
        PurchaseRepository purchaseRepository,
        ReceiptRepository receiptRepository,
        ReportJobRepository reportJobRepository
    ) {
        this.userRepository = userRepository;
        this.purchaseRepository = purchaseRepository;
        this.receiptRepository = receiptRepository;
        this.reportJobRepository = reportJobRepository;
    }

    public AdminOverviewResponse getOverview() {
        List<AdminUserResponse> recentUsers = userRepository.findTop10ByOrderByCreatedAtDescIdDesc()
            .stream()
            .map(this::mapUser)
            .toList();

        return new AdminOverviewResponse(
            userRepository.count(),
            purchaseRepository.count(),
            receiptRepository.count(),
            reportJobRepository.count(),
            recentUsers
        );
    }

    private AdminUserResponse mapUser(User user) {
        return new AdminUserResponse(
            user.getId(),
            user.getEmail(),
            user.getRole().name(),
            user.getGoogleSubject() == null ? "PASSWORD" : "GOOGLE",
            user.getCreatedAt()
        );
    }
}
