package com.conductor.integration;

import java.time.Instant;

/**
 * A short-lived, connector-issued runtime credential (e.g. a GitHub installation token) for injection
 * into a workflow step's execution environment (a {@code claude-code} container's env). Never
 * persisted, never logged, never exposed via {@code steps.*.outputs} — callers must treat
 * {@link #value()} as a bare secret and only ever write it into a process env map.
 */
public record RuntimeCredential(String envHint, String value, Instant expiresAt) {}
