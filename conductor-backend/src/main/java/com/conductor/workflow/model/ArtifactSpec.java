package com.conductor.workflow.model;

import java.util.regex.Pattern;

/**
 * One artifact a {@code docker}/{@code claude-code} step declares it produces (a step-level
 * {@code artifacts:} list entry). {@code path} is workspace-relative, resolved inside the step's
 * container.
 *
 * @param name artifact name, referenced by downstream jobs as {@code ${{ needs.JOB.artifacts.NAME }}}
 *             (validated against {@link #NAME_PATTERN} by {@code WorkflowValidator})
 * @param path workspace-relative file path inside the producing step's container
 */
public record ArtifactSpec(String name, String path) {

    /**
     * Single source of truth for a valid artifact name — shared by {@code WorkflowValidator} (publish-time
     * YAML lint) and {@code WorkflowArtifactService} (server-side re-validation of the name a worker sends
     * at declare time, which never goes through YAML validation).
     */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_-]{1,160}$");
}
