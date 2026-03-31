package com.blyndov.homebudgetreceiptsmanager.controller;

import com.blyndov.homebudgetreceiptsmanager.dto.HealthResponse;
import com.blyndov.homebudgetreceiptsmanager.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return healthService.getHealth();
    }
}
