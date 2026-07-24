package com.conductor.integration;

/**
 * Optional scope hint passed to {@link CredentialConnector#issueRuntimeCredential}. {@code
 * repoFullName} is nullable — connectors that can't narrow scope to a single repository just ignore
 * it and issue an unscoped credential.
 */
public record CredentialRequest(String repoFullName) {}
