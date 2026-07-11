package com.conductor.workflow;

import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.service.RuntimeTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Resolves a job's {@code runs-on} value to the {@link CloudRunTarget} and image to launch it with.
 * Three sources, checked in order:
 * <ol>
 *   <li>the reserved {@code "cloud-run"} scalar — the operator-configured builtin target, backed by
 *       {@code gcp.cloudrun.*} properties (moved here from {@link GcpCloudRunJobLauncher}, which now
 *       takes a target per call instead of holding its own project/region/job-name);</li>
 *   <li>{@code "conductor"}/{@code "self-hosted"}/{@code null} — never resolvable here (the orchestrator
 *       routes {@code self-hosted} elsewhere; {@code conductor} and unset don't run claude-code at all)
 *       — returns empty, same as before named targets existed;</li>
 *   <li>anything else — looked up as a named, project-owned {@link RuntimeTarget} via
 *       {@link RuntimeTargetService}. Not found or not {@link RuntimeTargetStatus#ACTIVE} throws a
 *       typed exception rather than returning empty, so {@link ClaudeCodeStepExecutor} can tell "no
 *       such runs-on at all" (existing {@code CLAUDE_INVALID_RUNS_ON} semantics, case 2 above) apart
 *       from "this target exists but isn't ready" ({@code RUNTIME_TARGET_NOT_FOUND} /
 *       {@code RUNTIME_TARGET_NOT_READY}).</li>
 * </ol>
 */
@Component
public class RuntimeTargetResolver {

    private static final Set<String> NEVER_RESOLVABLE = Set.of("conductor", "self-hosted");

    private final String gcpProjectId;
    private final String region;
    private final String jobName;
    private final RuntimeTargetService runtimeTargetService;

    public RuntimeTargetResolver(@Value("${gcp.cloudrun.project-id:}") String gcpProjectId,
                                  @Value("${gcp.cloudrun.region:us-central1}") String region,
                                  @Value("${gcp.cloudrun.claude-job-name:conductor-claude-code}") String jobName,
                                  RuntimeTargetService runtimeTargetService) {
        this.gcpProjectId = gcpProjectId;
        this.region = region;
        this.jobName = jobName;
        this.runtimeTargetService = runtimeTargetService;
    }

    /**
     * @param projectId the Conductor project id — used to scope named {@code RuntimeTarget} lookups
     *                   (the builtin {@code cloud-run} target isn't project-scoped).
     * @param runsOn     the job's {@code runs-on} value.
     * @return the resolved target and image, or empty if {@code runsOn} isn't a claude-code-capable
     *         runtime at all (builtin conductor runner, self-hosted, or unset).
     * @throws RuntimeTargetNotFoundException if {@code runsOn} names a target that doesn't exist in the project.
     * @throws RuntimeTargetNotReadyException if the named target exists but isn't ACTIVE yet.
     */
    public Optional<ResolvedRuntime> resolve(String projectId, String runsOn) {
        if ("cloud-run".equals(runsOn)) {
            return Optional.of(new ResolvedRuntime(new CloudRunTarget(gcpProjectId, region, jobName, null),
                    RunnerImage.DEFAULT));
        }
        if (runsOn == null || NEVER_RESOLVABLE.contains(runsOn)) {
            return Optional.empty();
        }

        RuntimeTarget target = runtimeTargetService.findByProjectIdAndName(projectId, runsOn)
                .orElseThrow(() -> new RuntimeTargetNotFoundException(runsOn));
        if (target.getStatus() != RuntimeTargetStatus.ACTIVE) {
            throw new RuntimeTargetNotReadyException(runsOn, target.getStatus());
        }
        if (target.getConnectionId() == null) {
            // A named target must never fall back to the builtin operator credentials — a null
            // connectionId on a CloudRunTarget means exactly that (see CloudRunClientFactory.forTarget),
            // so an orphaned target (its gcp connection deleted; FK is ON DELETE SET NULL) is unusable.
            throw new RuntimeTargetNotReadyException(runsOn, "its GCP connection was removed");
        }

        RuntimeTargetService.TargetRuntimeConfig config = runtimeTargetService.configOf(target);
        CloudRunTarget cloudRunTarget = new CloudRunTarget(
                config.gcpProjectId(), config.region(), config.jobName(), target.getConnectionId());
        return Optional.of(new ResolvedRuntime(cloudRunTarget, config.image()));
    }

    public record ResolvedRuntime(CloudRunTarget target, String image) {
    }
}
