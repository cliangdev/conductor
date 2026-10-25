package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the system Creative-render workflow's {@code docker} step on the workspace's designated BYO Cloud
 * Run runtime target: launches the target's render Job (provisioned by {@code RuntimeTargetService} from
 * the target's {@code renderImage}) through {@link CloudRunStepRunner}. The job's entrypoint,
 * {@code conductor-step-entrypoint}, runs the step's {@code run:} script (handed over as
 * {@code CONDUCTOR_STEP_RUN}) and reports logs and the result over the {@code /internal/v1} protocol,
 * exactly as the claude-code container does.
 *
 * <p>The target is resolved with {@link RuntimeTargetResolver#resolveRenderTarget}, never
 * {@code resolve("cloud-run")}: that would fall back to the operator's builtin target, which has no render
 * Job. If the target became unusable between dispatch and now, the step fails with
 * {@code RENDER_TARGET_UNAVAILABLE} naming it — no silent fallback.
 */
@Component
public class RenderTargetStepRunner {

    static final int DEFAULT_TIMEOUT_MINUTES = 30;
    /** The render Job's task timeout is 35 minutes, so a longer step budget could never be honoured. */
    static final int MAX_TIMEOUT_MINUTES = 35;

    static final String IMAGE_OUTDATED_REASON = "RENDER_IMAGE_OUTDATED: the render image has no "
            + "conductor-step-entrypoint; mirror the current conductor-render image and re-sync the target";

    private static final List<String> COMMAND = List.of("conductor-step-entrypoint");
    /**
     * Minutes past the step's own budget before Cloud Run kills the execution (and the poll gives up): the
     * entrypoint enforces {@code CONDUCTOR_TIMEOUT_MINUTES} itself (SIGTERM, then SIGKILL) and needs this
     * long to report STEP_TIMEOUT. The render Job's task timeout is MAX_TIMEOUT_MINUTES plus this.
     */
    static final int TIMEOUT_GRACE_MINUTES = 3;

    private static final CloudRunStepRunner.Reasons REASONS = new CloudRunStepRunner.Reasons(
            "RENDER_LAUNCH_ERROR", "STEP_TIMEOUT", "STEP_CONFIG_ERROR", RenderTargetStepRunner::mapExitCode,
            RenderTargetStepRunner::mapFailureMessage, RenderTargetStepRunner::mapSilentExit);

    private final CloudRunStepRunner stepRunner;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowInterpolator interpolator;
    private final String backendBaseUrl;

    public RenderTargetStepRunner(CloudRunStepRunner stepRunner,
                                  RuntimeTargetResolver runtimeTargetResolver,
                                  RunTokenService runTokenService,
                                  ProjectSettingsRepository projectSettingsRepository,
                                  WorkflowStepRunRepository stepRunRepository,
                                  WorkflowInterpolator interpolator,
                                  @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.stepRunner = stepRunner;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.stepRunRepository = stepRunRepository;
        this.interpolator = interpolator;
        this.backendBaseUrl = backendBaseUrl;
    }

    public StepResult run(StepExecutionContext context) {
        String projectId = context.getProjectId();
        Optional<WorkflowStepRun> priorRow = priorStepRow(context);
        RuntimeTargetResolver.RenderTargetResolution resolution;
        if (priorRow.isPresent()) {
            // Resuming after a restart: re-attach to the execution this step already started. The
            // "available for rendering" gate does not apply — only the target row + connection are needed.
            resolution = runtimeTargetResolver.resolveRenderTargetForResume(projectId, dispatchedTargetName(context));
            if (!resolution.available() && !isTerminal(priorRow.get())) {
                String execution = priorRow.get().getExecutionName() != null
                        ? priorRow.get().getExecutionName() : priorRow.get().getOperationName();
                return StepResult.failed("", "RENDER_TARGET_UNAVAILABLE: " + describe(context, resolution)
                        + ". The render's Cloud Run execution `" + execution + "` could not be tracked; "
                        + "check its status in the Cloud Run console.");
            }
        } else {
            resolution = runtimeTargetResolver.resolveRenderTarget(projectId);
            if (!resolution.available()) {
                return StepResult.failed("", "RENDER_TARGET_UNAVAILABLE: " + describe(context, resolution));
            }
        }

        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();
        Object runObj = stepDef.get("run");
        if (!(runObj instanceof String script) || script.isBlank()) {
            return StepResult.failed("", "STEP_CONFIG_ERROR: the render step has no 'run' script");
        }
        String interpolatedScript = interpolator.interpolate(script, ctx);
        int timeoutMinutes = resolveTimeoutMinutes(stepDef);
        String runId = context.getRun().getId();
        String jobId = context.getJobRun().getJobId();
        int ttlHours = projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getRunTokenTtlHours).orElse(24);

        boolean conductorMcp = conductorMcpEnabled(stepDef);

        CloudRunStepRunner.Spec spec = new CloudRunStepRunner.Spec(resolution.target(), resolution.image(), COMMAND,
                timeoutMinutes, "docker",
                workerJobId -> buildEnv(stepDef, ctx, interpolatedScript, projectId, runId, jobId, workerJobId,
                        timeoutMinutes, ttlHours, conductorMcp),
                REASONS, TIMEOUT_GRACE_MINUTES);
        return stepRunner.run(context, spec);
    }

    /** The step's row when it already launched (or finished) an execution, i.e. this run is a resume. */
    private Optional<WorkflowStepRun> priorStepRow(StepExecutionContext context) {
        Object stepId = context.getStepDefinition().get("id");
        if (!(stepId instanceof String id)) {
            return Optional.empty();
        }
        return stepRunRepository.findByJobRunIdAndStepId(context.getJobRun().getId(), id)
                .filter(row -> row.getWorkerJobId() != null
                        && (row.getExecutionName() != null || row.getOperationName() != null || isTerminal(row)));
    }

    private static boolean isTerminal(WorkflowStepRun row) {
        return row.getStatus() == WorkflowStepStatus.SUCCESS || row.getStatus() == WorkflowStepStatus.FAILED;
    }

    private static String dispatchedTargetName(StepExecutionContext context) {
        Object name = context.getRuntimeContext().getEventPayload().get("targetName");
        return name != null ? name.toString() : null;
    }

    /** {@code with.conductor_mcp}: only a step that asks for it gets a run-scoped API key. */
    private static boolean conductorMcpEnabled(Map<String, Object> stepDef) {
        if (stepDef.get("with") instanceof Map<?, ?> with) {
            Object value = with.get("conductor_mcp");
            return Boolean.TRUE.equals(value) || (value instanceof String s && "true".equalsIgnoreCase(s.trim()));
        }
        return false;
    }

    private Map<String, String> buildEnv(Map<String, Object> stepDef, RuntimeContext ctx, String script,
                                         String projectId, String runId, String jobId, String workerJobId,
                                         int timeoutMinutes, int ttlHours, boolean conductorMcp) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CONDUCTOR_API_URL", backendBaseUrl);
        env.put("CONDUCTOR_PROJECT_ID", projectId);
        env.put("CONDUCTOR_WORKFLOW_RUN_ID", runId);
        env.put("CONDUCTOR_JOB_ID", jobId);
        env.put("CONDUCTOR_WORKER_JOB_ID", workerJobId);
        env.put("CONDUCTOR_RUN_TOKEN", runTokenService.generateRunToken(runId, ttlHours));
        env.put("CONDUCTOR_LOG_CHUNK_URL", backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/log-chunk");
        env.put("CONDUCTOR_STEP_COMPLETE_URL",
                backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/steps/" + workerJobId + "/complete");
        env.put("CONDUCTOR_TIMEOUT_MINUTES", String.valueOf(timeoutMinutes));
        env.put("CONDUCTOR_MCP_ENABLED", String.valueOf(conductorMcp));
        if (conductorMcp) {
            env.put("CONDUCTOR_API_KEY", runTokenService.generateMcpToken(runId, projectId, ttlHours));
        }
        env.put("CONDUCTOR_STEP_RUN", script);

        if (stepDef.get("env") instanceof Map<?, ?> stepEnv) {
            for (Map.Entry<?, ?> entry : stepEnv.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (ReservedEnvKeys.isReserved(key)) {
                    throw new CredentialResolutionException(
                            "'" + key + "' is a reserved env key and cannot be set via env:");
                }
                env.put(key, entry.getValue() != null ? interpolator.interpolate(entry.getValue().toString(), ctx) : "");
            }
        }
        return env;
    }

    private static String describe(StepExecutionContext context, RuntimeTargetResolver.RenderTargetResolution resolution) {
        String name = resolution.targetName();
        if (name == null && context.getRuntimeContext().getEventPayload().get("targetName") != null) {
            // The designation is gone (target deleted), but the dispatch recorded which target it chose.
            name = context.getRuntimeContext().getEventPayload().get("targetName").toString();
        }
        String reason = resolution.unavailableReason();
        if (name != null && !reason.contains("'" + name + "'")) {
            reason = "runtime target '" + name + "': " + reason;
        }
        return reason + ". This run was dispatched to the target; render again to use the daemon instead.";
    }

    private int resolveTimeoutMinutes(Map<String, Object> stepDef) {
        int minutes = DEFAULT_TIMEOUT_MINUTES;
        if (stepDef.get("timeout_minutes") instanceof Number n) {
            minutes = n.intValue();
        }
        return Math.min(Math.max(minutes, 1), MAX_TIMEOUT_MINUTES);
    }

    /** Container exit code → errorReason, used only when the container never self-reported one. */
    static String mapExitCode(int exitCode) {
        return switch (exitCode) {
            case 20 -> "STEP_CONFIG_ERROR";
            case 124 -> "STEP_TIMEOUT";
            // 127 here is the step script's own "command not found": a render image without the
            // entrypoint fails at container start and is recognised from Cloud Run's message instead.
            case 127 -> "STEP_FAILED (exit 127): a command in the render step was not found";
            default -> "STEP_FAILED (exit " + exitCode + ")";
        };
    }

    /** Cloud Run's failure text for an execution whose container could not start the entrypoint. */
    static String mapFailureMessage(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        boolean missingEntrypoint = m.contains("executable file not found")
                || m.contains("conductor-step-entrypoint")
                || m.contains("container failed to start");
        return missingEntrypoint ? IMAGE_OUTDATED_REASON : null;
    }

    /**
     * conductor-step-entrypoint always reports something (log chunks, a step-complete, or exit 20 on missing
     * env), so a non-zero exit with no self-report and no streamed output means it never ran. Cloud Run's
     * execution status does not say so (it reports only NonZeroExitCode), hence this signal.
     */
    static String mapSilentExit(int exitCode) {
        // 20/124 are the entrypoint's own codes; 137/143 are the platform killing it (OOM, SIGTERM).
        if (exitCode == 20 || exitCode == 124 || exitCode == 137 || exitCode == 143) {
            return null;
        }
        return IMAGE_OUTDATED_REASON + " (the container exited with code " + exitCode
                + " before conductor-step-entrypoint reported anything)";
    }
}
