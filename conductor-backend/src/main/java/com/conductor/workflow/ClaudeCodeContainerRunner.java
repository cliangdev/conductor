package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.Connection;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.CredentialConnector;
import com.conductor.integration.CredentialRequest;
import com.conductor.integration.RuntimeCredential;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.ActiveConnectionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a single headless Claude Code container execution to completion: resolves the {@code runs-on}
 * target, checks subscription credentials, launches a Cloud Run Job execution
 * ({@link CloudRunJobLauncher}), polls to completion, and reads the result from the
 * {@link WorkflowStepRun} row the container self-reported into via the {@code /internal/v1}
 * step-complete callback. Extracted from {@link ClaudeCodeStepExecutor} (the {@code claude-code} step
 * type's original, sole caller) so a second caller — {@link ClaudeCodeAgentStepRuntime}, the
 * {@code agent} step's {@code claude-code} runtime — can reuse the exact same container-execution
 * mechanics without depending on the step-type executor.
 *
 * <p>Every caller-varying knob ({@code prompt}, {@code allowedTools}, {@code maxTurns},
 * {@code timeoutMinutes}, {@code conductorMcp}, {@code outputSchema}, and the {@code stepType} recorded
 * on the {@link WorkflowStepRun} row) comes from the {@link ClaudeCodeInvocation} the caller builds;
 * step-level concerns that aren't part of a "what should Claude Code do" call — {@code inputs},
 * {@code artifacts}, {@code consumes} — are still read directly off {@link StepExecutionContext}.
 *
 * <h2>Credentials</h2>
 * Every invocation is subscription-auth only, on every runtime: the container is the subscription
 * runtime, and the {@link ProviderCredentialService} credential stored under provider id
 * {@code claude-code} — a Claude Code OAuth token from {@code claude setup-token}, distinct from the
 * {@code claude} provider the {@code agent} step's {@code api} runtime's Anthropic API key lives under
 * — is injected as {@code CLAUDE_CODE_OAUTH_TOKEN}. {@code ANTHROPIC_API_KEY} is never set by this
 * class. When {@code conductorMcp} is true, the container also needs credentials for its Conductor MCP
 * server: rather than requiring a user-created project API key, this runner mints a short-lived,
 * run-scoped token via {@link RunTokenService#generateMcpToken}.
 *
 * <h2>Runs-on defaulting for agent-step calls</h2>
 * A raw {@code claude-code} step requires its job to declare a container-capable {@code runs-on}
 * ({@code cloud-run}, a named runtime target, or {@code self-hosted} — routed to the daemon before
 * reaching here) — an unresolvable {@code runs-on} is a hard {@code CLAUDE_INVALID_RUNS_ON} error, and
 * that strict behavior is unchanged. An {@code agent} step, by contrast, has no {@code runs-on} concept
 * of its own — the job it lives in may well be the Conductor-hosted default. So when
 * {@link ClaudeCodeInvocation#stepType()} is {@code "agent"} and the job's {@code runs-on} doesn't
 * resolve to a container target, this runner retries resolution against the builtin {@code cloud-run}
 * target before failing, rather than making every agent-step author add a {@code runs-on: cloud-run} to
 * their job just to pick the claude-code runtime.
 *
 * <h2>Crash recovery</h2>
 * Launch, polling, resume and cancel mechanics live in {@link CloudRunStepRunner}, shared with
 * {@link RenderTargetStepRunner}; this class owns target resolution, credentials and the Claude env.
 */
@Component
public class ClaudeCodeContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeContainerRunner.class);

    /** Distinct from {@code "claude"}, the {@code agent} step's {@code api}-runtime provider. */
    private static final String CLAUDE_CODE_PROVIDER = "claude-code";
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final List<String> CONTAINER_COMMAND = List.of("conductor-claude-entrypoint");

    private static final CloudRunStepRunner.Reasons REASONS = new CloudRunStepRunner.Reasons(
            "CLAUDE_LAUNCH_ERROR", "CLAUDE_TIMEOUT", "CLAUDE_CREDENTIAL_ERROR",
            ClaudeCodeContainerRunner::mapExitCode);

    private final CloudRunStepRunner stepRunner;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final ProviderCredentialService credentialService;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;
    private final ActiveConnectionResolver activeConnectionResolver;
    private final ConnectorRegistry connectorRegistry;
    private final String backendBaseUrl;

    public ClaudeCodeContainerRunner(CloudRunStepRunner stepRunner,
                                      RuntimeTargetResolver runtimeTargetResolver,
                                      ProviderCredentialService credentialService,
                                      RunTokenService runTokenService,
                                      ProjectSettingsRepository projectSettingsRepository,
                                      WorkflowInterpolator interpolator,
                                      ObjectMapper objectMapper,
                                      ActiveConnectionResolver activeConnectionResolver,
                                      ConnectorRegistry connectorRegistry,
                                      @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.stepRunner = stepRunner;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.credentialService = credentialService;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
        this.activeConnectionResolver = activeConnectionResolver;
        this.connectorRegistry = connectorRegistry;
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * One caller-supplied invocation of the Claude Code container. {@code stepType} is recorded on the
     * {@link WorkflowStepRun} row ({@code "claude-code"} for a raw step, {@code "agent"} when driven by
     * the {@code agent} step's claude-code runtime) and also selects the runs-on defaulting behavior
     * described in this class's javadoc.
     *
     * @param credentials each entry a {@code {connector, as}} map — resolved into a connector-issued
     *                    runtime credential and injected under the env key named by {@code as}.
     * @param extraEnv    plain, already-interpolated env values from the step's {@code env:} block.
     */
    public record ClaudeCodeInvocation(
            String prompt,
            String allowedTools,
            Integer maxTurns,
            Integer timeoutMinutes,
            Boolean conductorMcp,
            Map<String, Object> outputSchema,
            String stepType,
            List<Map<String, Object>> credentials,
            Map<String, String> extraEnv) {}

    public StepResult run(StepExecutionContext context, ClaudeCodeInvocation inv) {
        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved;
        try {
            resolved = runtimeTargetResolver.resolve(context.getProjectId(), context.getRunsOn());
            if (resolved.isEmpty() && "agent".equals(inv.stepType())) {
                // agent steps have no runs-on concept of their own — default to the builtin cloud-run
                // target instead of requiring every agent-step job to declare one just to pick this
                // runtime.
                resolved = runtimeTargetResolver.resolve(context.getProjectId(), "cloud-run");
            }
        } catch (RuntimeTargetNotFoundException e) {
            return StepResult.failed("", "RUNTIME_TARGET_NOT_FOUND: " + e.getMessage());
        } catch (RuntimeTargetNotReadyException e) {
            return StepResult.failed("", "RUNTIME_TARGET_NOT_READY: " + e.getMessage());
        }
        if (resolved.isEmpty()) {
            return StepResult.failed("", "CLAUDE_INVALID_RUNS_ON: claude-code steps require the job to "
                    + "declare 'runs-on: cloud-run' (or a project runtime target, or 'runs-on: self-hosted' "
                    + "dispatched separately to the daemon). Got: " + context.getRunsOn());
        }
        CloudRunTarget target = resolved.get().target();
        String image = resolved.get().image();

        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();
        String projectId = context.getProjectId();
        String runId = context.getRun().getId();
        WorkflowJobRun jobRun = context.getJobRun();
        String stepId = (String) stepDef.get("id");

        if (inv.prompt() == null || inv.prompt().isBlank()) {
            return StepResult.failed("", "Step 'prompt' is required for claude-code step");
        }

        Optional<String> oauthToken = credentialService.resolveApiKey(projectId, CLAUDE_CODE_PROVIDER);
        if (oauthToken.isEmpty()) {
            return StepResult.failed("", "CLAUDE_SUBSCRIPTION_NOT_CONFIGURED: no Claude Code subscription "
                    + "token configured for this project. Run 'claude setup-token' and store the result as "
                    + "the project's Claude Code credential under Integrations → Google Cloud.");
        }

        int ttlHours = loadTokenTtlHours(projectId);
        boolean conductorMcp = Boolean.TRUE.equals(inv.conductorMcp());
        String conductorApiKey = conductorMcp ? runTokenService.generateMcpToken(runId, projectId, ttlHours) : null;

        int timeoutMinutes = resolveTimeoutMinutes(inv.timeoutMinutes());
        List<String> consumes = context.getConsumes();
        CloudRunStepRunner.Spec spec = new CloudRunStepRunner.Spec(target, image, CONTAINER_COMMAND,
                timeoutMinutes, inv.stepType(),
                workerJobId -> buildEnv(inv, stepDef, ctx, projectId, runId, jobRun, workerJobId,
                        timeoutMinutes, conductorMcp, conductorApiKey, oauthToken.get(), consumes, ttlHours),
                REASONS);
        return stepRunner.run(context, spec);
    }


    @SuppressWarnings("unchecked")
    private Map<String, String> buildEnv(ClaudeCodeInvocation inv, Map<String, Object> stepDef, RuntimeContext ctx,
                                          String projectId, String runId, WorkflowJobRun jobRun, String workerJobId,
                                          int timeoutMinutes, boolean conductorMcp, String conductorApiKey,
                                          String oauthToken, List<String> consumes, int ttlHours) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CONDUCTOR_STEP_PROMPT", inv.prompt());

        Object inputsObj = stepDef.get("inputs");
        if (inputsObj instanceof Map) {
            Map<String, String> inputs = new LinkedHashMap<>();
            ((Map<String, Object>) inputsObj).forEach((k, v) -> {
                String value = v != null ? interpolator.interpolate(v.toString(), ctx) : "";
                inputs.put(k, value);
            });
            if (!inputs.isEmpty()) {
                env.put("CONDUCTOR_STEP_INPUTS_JSON", toJson(inputs));
            }
        }

        if (inv.allowedTools() != null) {
            env.put("CONDUCTOR_ALLOWED_TOOLS", inv.allowedTools());
        }
        if (inv.maxTurns() != null) {
            env.put("CONDUCTOR_MAX_TURNS", String.valueOf(inv.maxTurns()));
        }
        if (inv.outputSchema() != null) {
            env.put("CONDUCTOR_OUTPUT_SCHEMA_JSON", toJson(inv.outputSchema()));
        }

        env.put("CONDUCTOR_TIMEOUT_MINUTES", String.valueOf(timeoutMinutes));
        env.put("CONDUCTOR_MCP_ENABLED", String.valueOf(conductorMcp));
        env.put("CONDUCTOR_API_URL", backendBaseUrl);
        env.put("CONDUCTOR_PROJECT_ID", projectId);
        env.put("CONDUCTOR_WORKFLOW_RUN_ID", runId);
        env.put("CONDUCTOR_JOB_ID", jobRun.getJobId());
        env.put("CONDUCTOR_WORKER_JOB_ID", workerJobId);

        env.put("CONDUCTOR_RUN_TOKEN", runTokenService.generateRunToken(runId, ttlHours));
        env.put("CONDUCTOR_LOG_CHUNK_URL", backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/log-chunk");
        env.put("CONDUCTOR_STEP_COMPLETE_URL",
                backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/steps/" + workerJobId + "/complete");

        if (conductorMcp) {
            env.put("CONDUCTOR_API_KEY", conductorApiKey);
        }
        // Subscription auth only, on every runtime this runner launches — never ANTHROPIC_API_KEY.
        env.put("CLAUDE_CODE_OAUTH_TOKEN", oauthToken);

        Object artifactsObj = stepDef.get("artifacts");
        if (artifactsObj instanceof List<?> declaredArtifacts && !declaredArtifacts.isEmpty()) {
            env.put("CONDUCTOR_ARTIFACTS_URL", backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/artifacts");
            env.put("CONDUCTOR_STEP_ARTIFACTS_JSON", toJson(declaredArtifacts));
        }

        if (consumes != null && !consumes.isEmpty()) {
            List<Map<String, String>> consumedArtifacts = new ArrayList<>();
            for (String name : consumes) {
                String downloadUrl = findJobArtifactUrl(ctx, name);
                if (downloadUrl != null) {
                    consumedArtifacts.add(Map.of("name", name, "downloadUrl", downloadUrl));
                }
            }
            if (!consumedArtifacts.isEmpty()) {
                env.put("CONDUCTOR_CONSUMED_ARTIFACTS_JSON", toJson(consumedArtifacts));
                env.put("CONDUCTOR_ARTIFACTS_DIR", "/conductor/artifacts");
            }
        }

        applyCredentials(inv, ctx, projectId, env);
        applyExtraEnv(inv, env);
        return env;
    }

    /**
     * Resolves each {@code credentials:} entry ({@code {connector, as}}) into a connector-issued
     * {@link RuntimeCredential} and writes it into {@code env} under the key named by {@code as}.
     * Failure to resolve a connection or a CREDENTIAL-capable connector, or a reserved-key collision,
     * fails the step loudly via {@link CredentialResolutionException} rather than silently skipping or
     * overwriting a reserved var.
     */
    private void applyCredentials(ClaudeCodeInvocation inv, RuntimeContext ctx, String projectId, Map<String, String> env) {
        if (inv.credentials() == null || inv.credentials().isEmpty()) {
            return;
        }
        String repoHint = repoHint(ctx);
        for (Map<String, Object> entry : inv.credentials()) {
            Object connectorIdObj = entry.get("connector");
            Object asObj = entry.get("as");
            String connectorId = connectorIdObj != null ? connectorIdObj.toString() : null;
            String as = asObj != null ? asObj.toString() : null;
            if (connectorId == null || connectorId.isBlank() || as == null || as.isBlank()) {
                throw new CredentialResolutionException(
                        "credentials: entry requires both 'connector' and 'as': " + entry);
            }
            if (ReservedEnvKeys.isReserved(as)) {
                throw new CredentialResolutionException("'" + as + "' is a reserved env key and cannot be used in credentials:");
            }
            Connection connection = activeConnectionResolver.resolve(projectId, connectorId)
                    .orElseThrow(() -> new CredentialResolutionException(
                            "No active connection found for connector: " + connectorId));
            CredentialConnector credentialConnector = connectorRegistry.findCredential(connectorId)
                    .orElseThrow(() -> new CredentialResolutionException(
                            "Connector '" + connectorId + "' does not support issuing runtime credentials"));
            RuntimeCredential credential = credentialConnector.issueRuntimeCredential(connection, new CredentialRequest(repoHint));
            env.put(as, credential.value());
        }
    }

    /** Merges {@code env:} (already-interpolated by the caller) into the container env, rejecting any
     *  key that collides with a reserved Conductor var. */
    private void applyExtraEnv(ClaudeCodeInvocation inv, Map<String, String> env) {
        if (inv.extraEnv() == null || inv.extraEnv().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : inv.extraEnv().entrySet()) {
            if (ReservedEnvKeys.isReserved(entry.getKey())) {
                throw new CredentialResolutionException(
                        "'" + entry.getKey() + "' is a reserved env key and cannot be set via env:");
            }
            env.put(entry.getKey(), entry.getValue());
        }
    }

    /** Best-effort repo scope hint for a credential request — present for github.pull_request-triggered
     *  runs, {@code null} otherwise (an unscoped credential is still correct, just less tightly scoped). */
    private String repoHint(RuntimeContext ctx) {
        Object value = ctx.getEventPayload().get("repoFullName");
        return value != null ? value.toString() : null;
    }

    /** Searches every needed job's resolved artifacts (see {@link RuntimeContext#getJobArtifacts()}) for one named {@code name}. */
    private String findJobArtifactUrl(RuntimeContext ctx, String name) {
        for (Map<String, String> jobArtifacts : ctx.getJobArtifacts().values()) {
            String url = jobArtifacts.get(name);
            if (url != null) return url;
        }
        return null;
    }

    /** Container exit-code → errorReason fallback, used only when the container never self-reported one. */
    private static String mapExitCode(int exitCode) {
        return switch (exitCode) {
            case 10 -> "CLAUDE_AGENT_ERROR";
            case 11 -> "CLAUDE_AUTH_ERROR";
            case 12 -> "CLAUDE_RATE_LIMITED";
            case 13 -> "CLAUDE_TIMEOUT";
            case 20 -> "CLAUDE_CONFIG_ERROR";
            default -> "CLAUDE_LAUNCH_ERROR";
        };
    }


    private int loadTokenTtlHours(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getRunTokenTtlHours)
                .orElse(24);
    }


    private int resolveTimeoutMinutes(Integer requested) {
        int minutes = requested != null ? requested : DEFAULT_TIMEOUT_MINUTES;
        if (minutes < 1) return 1;
        if (minutes > MAX_TIMEOUT_MINUTES) return MAX_TIMEOUT_MINUTES;
        return minutes;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
