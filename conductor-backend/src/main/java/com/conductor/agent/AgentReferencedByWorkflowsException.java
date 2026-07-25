package com.conductor.agent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@link AgentService#delete} when the agent is still referenced (by slug or id) from an
 * {@code agent} step's {@code with.agent} in one or more automation workflows. Deleting would leave
 * those steps pointing at nothing, so the caller must repoint or remove the referencing workflows
 * first.
 */
public class AgentReferencedByWorkflowsException extends RuntimeException {

    private final List<Reference> references;

    public AgentReferencedByWorkflowsException(List<Reference> references) {
        super("Cannot delete agent: referenced by " + references.size() + " workflow(s): "
                + references.stream().map(Reference::workflowName).collect(Collectors.joining(", ")));
        this.references = references;
    }

    public List<Reference> references() {
        return references;
    }

    public record Reference(String workflowId, String workflowName) {
    }
}
