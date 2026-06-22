package com.conductor.repository;

import com.conductor.entity.Connection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, String> {
    List<Connection> findByProjectIdAndConnectorId(String projectId, String connectorId);
    List<Connection> findByProjectId(String projectId);
    Optional<Connection> findByIdAndConnectorId(String id, String connectorId);
}
