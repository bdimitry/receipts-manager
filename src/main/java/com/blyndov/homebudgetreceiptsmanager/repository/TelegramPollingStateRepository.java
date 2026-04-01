package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.TelegramPollingState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramPollingStateRepository extends JpaRepository<TelegramPollingState, Long> {
}
