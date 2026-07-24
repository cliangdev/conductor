package com.conductor.workflow;

/**
 * Thrown by {@link ClaudeCodeContainerRunner#buildEnv} when a step's {@code credentials:} entry (or a
 * reserved-key collision in {@code credentials:}/{@code env:}) can't be resolved into an injectable
 * env value — no ACTIVE connection for the named connector, the connector doesn't implement {@link
 * com.conductor.integration.CredentialConnector}, or the requested key is reserved. Caught by {@link
 * ClaudeCodeContainerRunner#run}, which fails the step with the message as its errorReason.
 */
public class CredentialResolutionException extends RuntimeException {

    public CredentialResolutionException(String message) {
        super(message);
    }
}
