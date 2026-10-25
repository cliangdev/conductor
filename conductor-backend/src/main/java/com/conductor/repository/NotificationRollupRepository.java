package com.conductor.repository;

import com.conductor.entity.NotificationRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRollupRepository extends JpaRepository<NotificationRollup, String> {

    /**
     * Row-level lock on the channel's config, held until the rollup's own (short) transaction ends. Two notification
     * events for the same channel arriving together would otherwise both read the same lines, each add
     * its own item, and each write back -- losing one item's line, or (when both find the window closed)
     * posting two messages where there should be one. Locking the config row rather than the rollup row
     * means the very first event for a channel, when no rollup row exists yet, is serialised too, so the
     * insert can never race.
     *
     * @return the config id when the row exists (and is now locked)
     */
    @Query(value = "SELECT id FROM notification_group_config WHERE id = :configId FOR UPDATE", nativeQuery = true)
    Optional<String> lockChannel(@Param("configId") String configId);

    /**
     * Caps how long this transaction waits for a lock ({@code SET LOCAL}, via {@code set_config(..., true)}
     * so it is scoped to the transaction and cannot leak into a pooled connection). A holder that is stuck
     * then costs one notification instead of a connection for as long as it lives.
     */
    @Query(value = "SELECT set_config('lock_timeout', '3s', true)", nativeQuery = true)
    String limitLockWait();
}
