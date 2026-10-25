package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.repository.ProjectSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final CloudRunStepRunner.Reasons REASONS = new CloudRunStepRunner.Reasons(
            "RENDER_LAUNCH_ERROR", "STEP_TIMEOUT", "STEP_CONFIG_ERROR", RenderTargetStepRunner::mapExitCode);

    private final CloudRunStepRunner stepRunner;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final String backendBaseUrl;

    public RenderTargetStepRunner(CloudRunStepRunner stepRunner,
                                  RuntimeTargetResolver runtimeTargetResolver,
                                  RunTokenService runTokenService,
                                  ProjectSettingsRepository projectSettingsRepository,
                                  WorkflowInterpolator interpolator,
                                  @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.stepRunner = stepRunner;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.interpolator = interpolator;
        this.backendBaseUrl = backendBaseUrl;
    }

    public StepResult run(StepExecutionContext context) {
        String projectId = context.getProjectId();
        RuntimeTargetResolver.RenderTargetResolution resolution = runtimeTargetResolver.resolveRenderTarget(projectId);
        if (!resolution.available()) {
            return StepResult.failed("", "RENDER_TARGET_UNAVAILABLE: " + describe(context, resolution));
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

        CloudRunStepRunner.Spec spec = new CloudRunStepRunner.Spec(resolution.target(), resolution.image(), COMMAND,
                timeoutMinutes, "docker",
                workerJobId -> buildEnv(stepDef, ctx, interpolatedScript, projectId, runId, jobId, workerJobId,
                        timeoutMinutes, ttlHours),
                REASONS);
        return stepRunner.run(context, spec);
    }

    private Map<String, String> buildEnv(Map<String, Object> stepDef, RuntimeContext ctx, String script,
                                         String projectId, String runId, String jobId, String workerJobId,
                                         int timeoutMinutes, int ttlHours) {
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
        env.put("CONDUCTOR_API_KEY", runTokenService.generateMcpToken(runId, projectId, ttlHours));
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
            case 127 -> IMAGE_OUTDATED_REASON;
            default -> "STEP_FAILED (exit " + exitCode + ")";
        };
    }
}
