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
 *
 * <p>This class is the single seam through which callers turn "which account?" into a
 * {@link Connection}. {@code ActionInvocationService#invoke} already takes a resolved
 * {@code Connection} rather than a connector id, so targeting a specific account needs no change
 * there — callers pick the connection here (by id via {@link #resolveById}, or by connector via
 * {@link #resolve}) and hand the result straight to {@code invoke}.
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

    /**
     * Resolves one explicitly named connection, so a project holding several connections for the
     * same connector (e.g. two Instagram accounts) can target exactly one of them instead of
     * whichever {@link #resolve} would pick.
     *
     * <p>The {@code projectId} filter is a tenancy boundary, not a convenience: a connection id
     * owned by another project must never resolve, however it reached us (workflow YAML, API
     * payload, agent-authored input). Callers should surface a single generic "not available"
     * failure for every empty result — unknown id, wrong project, and non-ACTIVE must be
     * indistinguishable to the caller so an id probe cannot confirm that a connection exists.
     *
     * @return the connection only when it exists, belongs to {@code projectId}, and is ACTIVE
     */
    public Optional<Connection> resolveById(String projectId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        return connectionRepository.findById(connectionId)
                .filter(c -> projectId != null && projectId.equals(c.getProjectId()))
                .filter(c -> "ACTIVE".equals(c.getStatus()));
    }
}
