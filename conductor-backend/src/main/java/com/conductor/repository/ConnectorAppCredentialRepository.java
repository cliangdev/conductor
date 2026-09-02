package com.conductor.repository;

import com.conductor.entity.ConnectorAppCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorAppCredentialRepository extends JpaRepository<ConnectorAppCredential, String> {

    Optional<ConnectorAppCredential> findByProjectIdAndConnectorId(String projectId, String connectorId);

    /** Every connector a project has brought its own app for — one query for a whole-catalog view. */
    List<ConnectorAppCredential> findByProjectId(String projectId);
}
