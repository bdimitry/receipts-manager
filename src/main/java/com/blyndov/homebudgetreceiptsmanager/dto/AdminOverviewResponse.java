package com.blyndov.homebudgetreceiptsmanager.dto;

import java.util.List;

public record AdminOverviewResponse(
    long usersCount,
    long purchasesCount,
    long receiptsCount,
    long reportJobsCount,
    List<AdminUserResponse> recentUsers
) {
}
