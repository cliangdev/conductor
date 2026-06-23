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

    /**
     * Per-connection dedup key. One provider delivery fans out to N connections (one row each), so
     * idempotency must be scoped to (connectionId, deliveryId), not globally to deliveryId.
     */
    Optional<WebhookEvent> findByConnectionIdAndDeliveryId(String connectionId, String deliveryId);

    List<WebhookEvent> findTop20ByConnectionIdOrderByReceivedAtDesc(String connectionId);

    /**
     * FAILED events due for processing. Intentionally has NO upper bound on attempts: the scheduler decides
     * retry vs. dead-letter. (A {@code attempts < maxAttempts} filter here made the DEAD branch unreachable —
     * an event at MAX_ATTEMPTS was never returned, so it stuck at FAILED forever.) The {@code maxAttempts}
     * parameter is retained for API stability but no longer constrains the result set.
     */
    @Query("SELECT e FROM WebhookEvent e WHERE e.status = com.conductor.entity.WebhookEventStatus.FAILED "
            + "AND (e.lastAttemptedAt IS NULL OR e.lastAttemptedAt < :cutoff)")
    List<WebhookEvent> findRetryable(@Param("maxAttempts") int maxAttempts,
                                     @Param("cutoff") OffsetDateTime cutoff);
}
