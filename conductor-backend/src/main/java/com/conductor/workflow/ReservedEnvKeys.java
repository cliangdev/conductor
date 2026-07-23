package com.conductor.workflow;

/**
 * Env var names/prefixes reserved for Conductor's own container wiring. A step's {@code
 * credentials:}/{@code env:} entries must never collide with these — used both by {@link
 * ClaudeCodeContainerRunner#buildEnv} (hard failure at execution time) and {@link WorkflowValidator}
 * (hard failure at publish time).
 */
final class ReservedEnvKeys {

    private ReservedEnvKeys() {
    }

    static boolean isReserved(String key) {
        return key != null && (key.startsWith("CONDUCTOR_") || "CLAUDE_CODE_OAUTH_TOKEN".equals(key));
    }
}
