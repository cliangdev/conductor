package com.conductor.repository;

import com.conductor.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, String> {
    List<Connection> findByProjectIdAndConnectorId(String projectId, String connectorId);
    List<Connection> findByProjectId(String projectId);
    Optional<Connection> findByIdAndConnectorId(String id, String connectorId);

    /**
     * Generic, connector-agnostic config-routing mechanism: looks up connections by a top-level
     * value inside {@code config_json}. This is the intended extensibility point for resolving a
     * connection from an inbound payload without a connector-specific column on this generic table
     * (e.g. resolving github installations by {@code installationId} for app-level webhook routing).
     *
     * <p>Returns ALL matching connections across projects — a single value (e.g. one github App
     * installationId) may be connected from multiple projects (multi-project fan-out), so callers
     * must dispatch to every returned connection. Within one project a given installationId is
     * unique (enforced by {@code uq_connection_github_installation_per_project}), so per-project
     * routing is unambiguous.
     */
    @Query(nativeQuery = true,
            value = "SELECT * FROM connection WHERE connector_id = :connectorId AND config_json->>:key = :value")
    List<Connection> findByConnectorIdAndConfigValue(@Param("connectorId") String connectorId,
                                                     @Param("key") String key,
                                                     @Param("value") String value);

}
