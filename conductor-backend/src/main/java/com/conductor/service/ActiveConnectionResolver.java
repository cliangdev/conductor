package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /**
     * An ACTIVE {@code PAT} row (an explicit, project-owned credential) wins over any other ACTIVE
     * row for the same (project, connector) — mirrors {@code RuntimeTargetResolver}'s "explicit
     * override wins over platform default" pattern (e.g. github's App-managed connection). Falls
     * back to the first ACTIVE row when no PAT exists. At most one ACTIVE PAT can exist per
     * (project, connector) — enforced by {@code uq_connection_pat_per_project_connector} — so this
     * preference is unambiguous.
     */
    public Optional<Connection> resolve(String projectId, String connectorId) {
        List<Connection> active = connectionRepository.findByProjectIdAndConnectorId(projectId, connectorId).stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .toList();
        return active.stream()
                .filter(c -> AuthType.PAT.name().equals(c.getAuthType()))
                .findFirst()
                .or(() -> active.stream().findFirst());
    }
}
