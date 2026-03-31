package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.HealthResponse;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final String STATUS_UP = "UP";
    private static final String SERVICE_NAME = "home-budget-receipts-manager";

    public HealthResponse getHealth() {
        return new HealthResponse(STATUS_UP, SERVICE_NAME);
    }
}
