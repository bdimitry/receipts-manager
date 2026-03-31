package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.NotificationChannel;
import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;
import com.blyndov.homebudgetreceiptsmanager.entity.User;

public interface NotificationChannelSender {

    NotificationChannel channel();

    boolean isConfigured(User user);

    void send(ReportJob reportJob, NotificationMessage message);
}
