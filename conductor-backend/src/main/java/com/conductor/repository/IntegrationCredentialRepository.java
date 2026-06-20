package com.conductor.repository;

import com.conductor.entity.IntegrationCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntegrationCredentialRepository extends JpaRepository<IntegrationCredential, String> {
    Optional<IntegrationCredential> findByProjectIdAndConnectorId(String projectId, String connectorId);
    List<IntegrationCredential> findByProjectId(String projectId);
    void deleteByProjectIdAndConnectorId(String projectId, String connectorId);
}
