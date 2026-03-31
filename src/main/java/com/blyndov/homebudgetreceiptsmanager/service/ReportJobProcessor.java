package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.entity.ReportJob;

public interface ReportJobProcessor {

    String process(ReportJob reportJob);
}
