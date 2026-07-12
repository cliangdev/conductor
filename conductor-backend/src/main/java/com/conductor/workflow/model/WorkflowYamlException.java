package com.conductor.workflow.model;

/**
 * Thrown by {@link WorkflowYamlParser} when workflow YAML is structurally unparseable (malformed
 * YAML syntax, or an empty document). Unchecked — a workflow's stored YAML is expected to already
 * be valid (gated at save time by {@code WorkflowValidator}), so callers that parse stored YAML at
 * execution time treat this the same way they treated a {@code null} result from the old
 * try/catch-and-log SnakeYAML calls: catch it, log, and fail gracefully rather than propagating.
 */
public class WorkflowYamlException extends RuntimeException {

    public WorkflowYamlException(String message) {
        super(message);
    }

    public WorkflowYamlException(String message, Throwable cause) {
        super(message, cause);
    }
}
