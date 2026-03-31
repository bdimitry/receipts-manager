package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;

public interface NotificationService {

    void sendReportReadyNotification(ReportJob reportJob);

    void sendReportFailedNotification(ReportJob reportJob);
}
