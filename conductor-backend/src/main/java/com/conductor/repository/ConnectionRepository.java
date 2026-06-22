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
     * Generic, connector-agnostic lookup of connections by a top-level value inside {@code config_json}.
     * Used e.g. to resolve installations by {@code installationId} for app-level webhook routing.
     */
    @Query(nativeQuery = true,
            value = "SELECT * FROM connection WHERE connector_id = :connectorId AND config_json->>:key = :value")
    List<Connection> findByConnectorIdAndConfigValue(@Param("connectorId") String connectorId,
                                                     @Param("key") String key,
                                                     @Param("value") String value);
}
