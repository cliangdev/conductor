package com.conductor.integration;

import com.conductor.entity.Connection;

/**
 * CREDENTIAL capability: mints a short-lived runtime credential (e.g. a GitHub installation token)
 * for injection into a workflow step's execution environment — a {@code claude-code} container's env,
 * via {@code ClaudeCodeContainerRunner#buildEnv}. Unlike {@link ActionConnector}, this is never
 * surfaced through a step's outputs; the caller resolves the connection, calls this synchronously, and
 * writes the result straight into the container's env map.
 */
public interface CredentialConnector extends Connector {
    RuntimeCredential issueRuntimeCredential(Connection connection, CredentialRequest request);
}
