package com.conductor.repository;

import com.conductor.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, String> {
    List<Connection> findByProjectIdAndConnectorId(String projectId, String connectorId);

    /**
     * What a destination picker reads off a connection: identity, status, config and health - never the
     * encrypted token columns, which a full {@link Connection} row drags across the wire (and through KMS-aware
     * converters) for nothing.
     */
    interface PublishableConnection {
        String getId();
        String getConnectorId();
        String getStatus();
        String getConfigJson();
        String getDisplayLabel();
        String getHealthStatus();
        String getHealthMessage();
        String getHealthDetail();
    }

    /** Several connectors' connections in one query, as {@link PublishableConnection}s. */
    @Query("SELECT c.id AS id, c.connectorId AS connectorId, c.status AS status, c.configJson AS configJson, "
            + "c.displayLabel AS displayLabel, c.healthStatus AS healthStatus, c.healthMessage AS healthMessage, "
            + "c.healthDetail AS healthDetail FROM Connection c "
            + "WHERE c.projectId = :projectId AND c.connectorId IN :connectorIds")
    List<PublishableConnection> findPublishableByProjectIdAndConnectorIdIn(@Param("projectId") String projectId,
                                                                           @Param("connectorIds") java.util.Collection<String> connectorIds);
    List<Connection> findByProjectId(String projectId);

    /** Atomically flags a stalled feed unless a more severe verdict holds the row or the same message is already set. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Connection c SET c.healthStatus = 'DEGRADED', c.healthMessage = :message, c.healthDetail = 'NO_NEW_DATA', "
            + "c.healthCheckedAt = CURRENT_TIMESTAMP WHERE c.id = :id "
            + "AND (c.healthStatus IS NULL OR c.healthStatus = 'HEALTHY' OR c.healthDetail = 'NO_NEW_DATA') "
            + "AND (c.healthDetail IS NULL OR c.healthDetail <> 'NO_NEW_DATA' OR c.healthMessage IS NULL OR c.healthMessage <> :message "
            + "OR c.healthStatus <> 'DEGRADED')")
    int markNoNewData(@Param("id") String id, @Param("message") String message);

    /** Atomically clears the stall flag, and only that flag. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Connection c SET c.healthStatus = 'HEALTHY', c.healthMessage = NULL, c.healthDetail = NULL, "
            + "c.healthCheckedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.healthDetail = 'NO_NEW_DATA'")
    int clearNoNewData(@Param("id") String id);
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
