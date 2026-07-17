package com.conductor.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Authentication for a run-scoped MCP token ({@link com.conductor.workflow.RunTokenService#parseMcpToken}) —
 * a backend-minted, short-lived credential a Claude Code container's Conductor MCP server presents on
 * behalf of a specific workflow run, scoped to that run's project.
 */
public class WorkflowRunAuthenticationToken extends AbstractAuthenticationToken implements ProjectScopedPrincipal {

    private final String projectId;
    private final String runId;

    public WorkflowRunAuthenticationToken(String projectId, String runId) {
        super(List.of());
        this.projectId = projectId;
        this.runId = runId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return projectId;
    }

    @Override
    public String getProjectId() {
        return projectId;
    }

    public String getRunId() {
        return runId;
    }
}
