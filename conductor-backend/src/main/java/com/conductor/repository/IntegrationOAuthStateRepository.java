package com.conductor.repository;

import com.conductor.entity.IntegrationOAuthState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface IntegrationOAuthStateRepository extends JpaRepository<IntegrationOAuthState, String> {
    void deleteByExpiresAtBefore(OffsetDateTime now);
}
