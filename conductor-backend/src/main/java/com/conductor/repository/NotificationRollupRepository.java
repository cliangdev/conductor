package com.conductor.repository;

import com.conductor.entity.NotificationRollup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRollupRepository extends JpaRepository<NotificationRollup, String> {

    /**
     * Row-level lock on a channel's rolling message. Two notification events for the same channel
     * arriving together would otherwise both read the same lines, each add its own item, and each write
     * back -- losing one item's line, or (when both find the window closed) posting two messages where
     * there should be one. The lock makes the second event wait and then see the first one's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from NotificationRollup r where r.configId = :configId")
    Optional<NotificationRollup> findByIdForUpdate(@Param("configId") String configId);
}
