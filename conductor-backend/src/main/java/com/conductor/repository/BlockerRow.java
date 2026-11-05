package com.conductor.repository;

/**
 * One {@code BLOCKS} link as the signal and graph reads need it: which item is blocked, and enough about the
 * blocker (its display id parts, title, status and the Workflow version that status is judged in) to decide
 * whether it still blocks and to name it, without loading the blocker entity.
 */
public record BlockerRow(String blockedId, String blockerId, String projectKey, Integer blockerSequence,
                         String blockerTitle, String blockerStatus, String blockerWorkflow,
                         Integer blockerWorkflowVersion) {

    public String blockerDisplayId() {
        return projectKey + "-" + blockerSequence;
    }
}
