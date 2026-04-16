package com.conductor.repository;

import com.conductor.entity.DaemonEvent;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface DaemonEventRepository extends JpaRepository<DaemonEvent, String> {

    List<DaemonEvent> findByProjectIdAndAckedAtIsNullAndExpiresAtAfter(String projectId, OffsetDateTime now);

    @Modifying
    @Transactional
    void deleteByExpiresAtBefore(OffsetDateTime cutoff);

    @Modifying
    @Transactional
    @Query("UPDATE DaemonEvent d SET d.ackedAt = :ackedAt WHERE d.id IN :ids AND d.projectId = :projectId")
    int acknowledgeEvents(@Param("ids") List<String> ids, @Param("projectId") String projectId, @Param("ackedAt") OffsetDateTime ackedAt);
}
