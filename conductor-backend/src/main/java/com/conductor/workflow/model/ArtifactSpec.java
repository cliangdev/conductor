package com.conductor.workflow.model;

/**
 * One artifact a {@code docker}/{@code claude-code} step declares it produces (a step-level
 * {@code artifacts:} list entry). {@code path} is workspace-relative, resolved inside the step's
 * container.
 *
 * @param name artifact name, referenced by downstream jobs as {@code ${{ needs.JOB.artifacts.NAME }}}
 *             (validated against {@code ^[a-z0-9_-]{1,160}$} by {@code WorkflowValidator})
 * @param path workspace-relative file path inside the producing step's container
 */
public record ArtifactSpec(String name, String path) {
}
