package com.conductor.agent.provider;

/**
 * One model a {@link ChatModelProvider} currently supports, as returned by
 * {@link ChatModelProvider#availableModels}. {@code latest} marks the provider's own pick for "the
 * newest general-purpose model I currently support" — at most one entry in a given list has
 * {@code latest == true}.
 */
public record ModelInfo(String id, boolean latest) {
}
