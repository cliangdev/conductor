package com.conductor.workflow;

import com.conductor.creative.CreativeWorkflowProvisioner;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;

import java.util.Set;

/**
 * Names of the workflows Conductor provisions itself. They are not the workspace's to budget: they do not
 * count toward {@code MAX_WORKFLOWS_PER_PROJECT}, and the circuit breaker never auto-pauses
 * {@link CreativeWorkflowProvisioner#RENDER_WORKFLOW_NAME} (a few failed renders must not silently switch
 * rendering off).
 */
public final class SystemWorkflows {

    public static final Set<String> NAMES = Set.of(
            KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME,
            KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME,
            KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME,
            CreativeWorkflowProvisioner.RENDER_WORKFLOW_NAME);

    private SystemWorkflows() {
    }

    public static boolean isSystem(String name) {
        return name != null && NAMES.contains(name);
    }
}
