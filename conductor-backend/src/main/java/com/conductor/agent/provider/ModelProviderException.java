package com.conductor.agent.provider;

/** Raised when a model provider call fails. {@code retryable} flags transient errors (429/5xx/IO). */
public class ModelProviderException extends RuntimeException {

    private final boolean retryable;

    public ModelProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
