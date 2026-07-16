package com.conductor.workflow;

/**
 * Thrown by {@link AgentRuntimeResolver#resolve} when an {@code agent} step's definition pins no
 * runtime and the project has neither a {@code claude-code} subscription credential nor an API key for
 * the agent's model provider. Caught by {@link AgentStepExecutor} and turned into a failed
 * {@link StepResult} — never propagates out of step execution.
 */
public class AgentRuntimeUnresolvedException extends RuntimeException {
    public AgentRuntimeUnresolvedException(String message) {
        super(message);
    }
}
