package com.conductor.repository;

import com.conductor.entity.IntegrationOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface IntegrationOAuthStateRepository extends JpaRepository<IntegrationOAuthState, String> {
    // Derived bulk-delete: needs an ambient transaction, otherwise the per-row remove throws
    // InvalidDataAccessApiUsageException ("No EntityManager with actual transaction available").
    @Transactional
    void deleteByExpiresAtBefore(OffsetDateTime now);
}
