package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.repository.ConnectionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the ACTIVE connection for a (projectId, connectorId) pair. Extracted from the identical
 * inline stream duplicated in {@code IntegrationStepExecutor} and {@code ActionStepExecutor}; also
 * used by {@code ClaudeCodeContainerRunner}'s credential-resolution path.
 */
@Component
public class ActiveConnectionResolver {

    private final ConnectionRepository connectionRepository;

    public ActiveConnectionResolver(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    public Optional<Connection> resolve(String projectId, String connectorId) {
        return connectionRepository.findByProjectIdAndConnectorId(projectId, connectorId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .findFirst();
    }
}
