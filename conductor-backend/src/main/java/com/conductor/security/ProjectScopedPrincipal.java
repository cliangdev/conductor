package com.conductor.security;

/**
 * Marks {@link org.springframework.security.core.Authentication} types whose principal is a project
 * id — machine-to-machine, project-scoped access (a project API key, a run-scoped MCP token) rather
 * than a human user. Controllers that need to authorize project access should check this one
 * abstraction instead of instanceof-ing each concrete token type.
 */
public interface ProjectScopedPrincipal {

    String getProjectId();
}
