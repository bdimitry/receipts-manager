package com.blyndov.homebudgetreceiptsmanager.controller;

import com.blyndov.homebudgetreceiptsmanager.dto.AdminOverviewResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.UserRole;
import com.blyndov.homebudgetreceiptsmanager.service.AdminService;
import com.blyndov.homebudgetreceiptsmanager.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final AuthService authService;

    public AdminController(AdminService adminService, AuthService authService) {
        this.adminService = adminService;
        this.authService = authService;
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview() {
        if (authService.getCurrentAuthenticatedUser().getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role is required");
        }
        return adminService.getOverview();
    }
}
