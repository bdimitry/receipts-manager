package com.blyndov.homebudgetreceiptsmanager.repository;

import com.blyndov.homebudgetreceiptsmanager.entity.TelegramConnectToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramConnectTokenRepository extends JpaRepository<TelegramConnectToken, Long> {

    void deleteAllByUser_Id(Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<TelegramConnectToken> findByToken(String token);

    Optional<TelegramConnectToken> findFirstByUser_IdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
        Long userId,
        Instant now
    );
}
