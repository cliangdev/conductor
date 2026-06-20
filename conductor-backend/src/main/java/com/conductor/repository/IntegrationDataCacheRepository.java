package com.conductor.repository;

import com.conductor.entity.IntegrationDataCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IntegrationDataCacheRepository extends JpaRepository<IntegrationDataCache, String> {
    Optional<IntegrationDataCache> findByProjectIdAndConnectorId(String projectId, String connectorId);
    void deleteByProjectIdAndConnectorId(String projectId, String connectorId);
}
