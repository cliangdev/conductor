package com.conductor.repository;

import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    Optional<WebhookEvent> findByDeliveryId(String deliveryId);

    List<WebhookEvent> findTop20ByConnectionIdOrderByReceivedAtDesc(String connectionId);

    @Query("SELECT e FROM WebhookEvent e WHERE e.status = com.conductor.entity.WebhookEventStatus.FAILED "
            + "AND e.attempts < :maxAttempts "
            + "AND (e.lastAttemptedAt IS NULL OR e.lastAttemptedAt < :cutoff)")
    List<WebhookEvent> findRetryable(@Param("maxAttempts") int maxAttempts,
                                     @Param("cutoff") OffsetDateTime cutoff);
}
