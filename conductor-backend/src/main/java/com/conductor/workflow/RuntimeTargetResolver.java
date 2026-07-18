package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.RuntimeTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Resolves a job's {@code runs-on} value to the {@link CloudRunTarget} and image to launch it with.
 * Three sources, checked in order:
 * <ol>
 *   <li>the reserved {@code "cloud-run"} scalar: the project's <em>designated</em> runtime target
 *       ({@link ProjectSettings#getClaudeRuntimeTargetId()}, set via {@code ClaudeRuntimeService} —
 *       must be {@link RuntimeTargetStatus#ACTIVE} with a live connection, else a typed "not ready"
 *       failure carrying the target's own {@code errorMessage}) if the project has designated one,
 *       otherwise the operator-configured <em>builtin</em> target backed by {@code gcp.cloudrun.*}
 *       properties (moved here from {@link GcpCloudRunJobLauncher}, which now takes a target per call
 *       instead of holding its own project/region/job-name) — a blank builtin project id throws
 *       {@link #NO_RUNTIME_MESSAGE} rather than silently returning an unusable target (the bug that
 *       prompted this class: an unset {@code gcp.cloudrun.project-id} surfaced as an opaque Cloud Run
 *       gRPC {@code INVALID_ARGUMENT} instead of an actionable step error). Explicit
 *       {@code runs-on: cloud-run} in workflow YAML goes through this same designation — one knob;
 *       authors wanting a specific BYO target pin it by name (case 3) instead;</li>
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

    /** Shared with {@code ClaudeCodeRuntimePreflight} so the probe and the real resolver never drift. */
    public static final String NO_RUNTIME_MESSAGE = "No Claude runtime configured — link a runtime "
            + "target in Settings → AI Providers → Runtime, or set GCP_CLOUDRUN_PROJECT_ID on the backend";

    private static final Set<String> NEVER_RESOLVABLE = Set.of("conductor", "self-hosted");
    private static final String CLOUD_RUN = "cloud-run";

    private final String gcpProjectId;
    private final String region;
    private final String jobName;
    private final RuntimeTargetService runtimeTargetService;
    private final ProjectSettingsRepository projectSettingsRepository;

    public RuntimeTargetResolver(@Value("${gcp.cloudrun.project-id:}") String gcpProjectId,
                                  @Value("${gcp.cloudrun.region:us-central1}") String region,
                                  @Value("${gcp.cloudrun.claude-job-name:conductor-claude-code}") String jobName,
                                  RuntimeTargetService runtimeTargetService,
                                  ProjectSettingsRepository projectSettingsRepository) {
        this.gcpProjectId = gcpProjectId;
        this.region = region;
        this.jobName = jobName;
        this.runtimeTargetService = runtimeTargetService;
        this.projectSettingsRepository = projectSettingsRepository;
    }

    /**
     * @param projectId the Conductor project id — used to scope named {@code RuntimeTarget} lookups
     *                   and to look up the project's {@code cloud-run} designation.
     * @param runsOn     the job's {@code runs-on} value.
     * @return the resolved target and image, or empty if {@code runsOn} isn't a claude-code-capable
     *         runtime at all (builtin conductor runner, self-hosted, or unset).
     * @throws RuntimeTargetNotFoundException if {@code runsOn} names a target that doesn't exist in the project.
     * @throws RuntimeTargetNotReadyException if the resolved target (named, designated, or builtin) isn't usable.
     */
    public Optional<ResolvedRuntime> resolve(String projectId, String runsOn) {
        if (CLOUD_RUN.equals(runsOn)) {
            return Optional.of(resolveCloudRun(projectId));
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

    /** Cheap, DB-free check of whether the operator-configured builtin target is usable at all — for
     *  {@code ClaudeRuntimeService.getConfig}'s {@code builtinConfigured} flag, so the UI can tell "no
     *  builtin fallback either" apart from "a target is designated" without duplicating this property
     *  binding. */
    public boolean isBuiltinConfigured() {
        return gcpProjectId != null && !gcpProjectId.isBlank();
    }

    private ResolvedRuntime resolveCloudRun(String projectId) {
        String designatedTargetId = projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getClaudeRuntimeTargetId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);

        if (designatedTargetId != null) {
            return resolveDesignatedTarget(projectId, designatedTargetId);
        }
        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            throw new RuntimeTargetNotReadyException(CLOUD_RUN, NO_RUNTIME_MESSAGE);
        }
        return new ResolvedRuntime(new CloudRunTarget(gcpProjectId, region, jobName, null), RunnerImage.DEFAULT);
    }

    private ResolvedRuntime resolveDesignatedTarget(String projectId, String targetId) {
        RuntimeTarget target;
        try {
            target = runtimeTargetService.get(projectId, targetId);
        } catch (RuntimeException e) {
            // FK is ON DELETE SET NULL, so this should be unreachable in practice — defensive only.
            throw new RuntimeTargetNotReadyException(CLOUD_RUN, "its designated runtime target no longer exists");
        }
        if (target.getStatus() != RuntimeTargetStatus.ACTIVE || target.getConnectionId() == null) {
            String reason = target.getErrorMessage() != null ? target.getErrorMessage()
                    : "designated runtime target '" + target.getName() + "' is not ready (status: "
                            + target.getStatus() + ")";
            throw new RuntimeTargetNotReadyException(CLOUD_RUN, reason);
        }
        RuntimeTargetService.TargetRuntimeConfig config = runtimeTargetService.configOf(target);
        CloudRunTarget cloudRunTarget = new CloudRunTarget(
                config.gcpProjectId(), config.region(), config.jobName(), target.getConnectionId());
        return new ResolvedRuntime(cloudRunTarget, config.image());
    }

    public record ResolvedRuntime(CloudRunTarget target, String image) {
    }
}
