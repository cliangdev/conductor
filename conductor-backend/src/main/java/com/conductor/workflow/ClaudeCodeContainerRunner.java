package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.Connection;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.CredentialConnector;
import com.conductor.integration.CredentialRequest;
import com.conductor.integration.RuntimeCredential;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ActiveConnectionResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * The Cloud Run RunJob operation name is persisted onto the step-run row as soon as
 * {@link CloudRunJobLauncher#startExecution} returns — even before the execution name itself is
 * confirmed, since that confirmation is genuinely async and can outlast a single request. The execution
 * name is persisted as soon as it resolves (immediately, if prompt; otherwise once
 * {@link CloudRunJobLauncher#tryResolveExecutionName} succeeds). Either way, a backend restart re-attaches
 * via whichever of the two is known rather than relaunching a duplicate execution — Cloud Run's
 * RunJobRequest has no idempotency key, so a blind retry risks starting a second, real container. See
 * {@link #resolveOrCreateStepRun} and the resume branches at the top of {@link #run}.
 */
@Component
public class ClaudeCodeContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeContainerRunner.class);

    /** Distinct from {@code "claude"}, the {@code agent} step's {@code api}-runtime provider. */
    private static final String CLAUDE_CODE_PROVIDER = "claude-code";
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final int POLL_INTERVAL_SECONDS = 10;
    private static final List<String> CONTAINER_COMMAND = List.of("conductor-claude-entrypoint");

    private final CloudRunJobLauncher launcher;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final ProviderCredentialService credentialService;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowRunRepository runRepository;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;
    private final WorkflowRunLogBroker logBroker;
    private final ActiveConnectionResolver activeConnectionResolver;
    private final ConnectorRegistry connectorRegistry;
    private final String backendBaseUrl;

    public ClaudeCodeContainerRunner(CloudRunJobLauncher launcher,
                                      RuntimeTargetResolver runtimeTargetResolver,
                                      ProviderCredentialService credentialService,
                                      WorkflowStepRunRepository stepRunRepository,
                                      WorkflowRunRepository runRepository,
                                      RunTokenService runTokenService,
                                      ProjectSettingsRepository projectSettingsRepository,
                                      WorkflowInterpolator interpolator,
                                      ObjectMapper objectMapper,
                                      WorkflowRunLogBroker logBroker,
                                      ActiveConnectionResolver activeConnectionResolver,
                                      ConnectorRegistry connectorRegistry,
                                      @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.launcher = launcher;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.credentialService = credentialService;
        this.stepRunRepository = stepRunRepository;
        this.runRepository = runRepository;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
        this.logBroker = logBroker;
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

        WorkflowStepRun stepRun = resolveOrCreateStepRun(jobRun, stepId, stepDef, inv.stepType());
        String workerJobId = stepRun.getWorkerJobId();

        Optional<WorkflowStepRun> priorRow = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRun.getId(), workerJobId);
        if (priorRow.isPresent() && isTerminal(priorRow.get().getStatus())) {
            log.info("claude-code step {} already terminal on resume (workerJobId={}), skipping relaunch",
                    stepId, workerJobId);
            return resultFromRow(priorRow.get(), stepDef, Optional.empty(), "Resumed from prior run\n");
        }

        int timeoutMinutes = resolveTimeoutMinutes(inv.timeoutMinutes());
        StringBuilder logBuilder = new StringBuilder();

        if (priorRow.isPresent() && priorRow.get().getExecutionName() != null) {
            WorkflowStepRun inFlightRow = priorRow.get();
            String executionName = inFlightRow.getExecutionName();
            int remainingMinutes = remainingTimeoutMinutes(inFlightRow, timeoutMinutes);
            log.info("claude-code step {} resuming poll on prior Cloud Run execution {} (workerJobId={}, "
                    + "{}m remaining of {}m budget), skipping relaunch",
                    stepId, executionName, workerJobId, remainingMinutes, timeoutMinutes);
            appendLauncherLine(inFlightRow, projectId, logBuilder,
                    "→ Resuming poll on prior Cloud Run execution: " + executionName);
            return pollUntilTerminal(target, executionName, null, runId, jobRun.getId(), workerJobId, stepDef,
                    remainingMinutes, logBuilder, inFlightRow, projectId);
        }

        // The launch was previously accepted by Cloud Run (operationName persisted) but never resolved
        // to a confirmed executionName before this row was last touched — e.g. a backend restart mid-
        // launch. Resume checking rather than relaunching: RunJobRequest has no idempotency key, so a
        // blind retry here risks starting a second, duplicate execution.
        if (priorRow.isPresent() && priorRow.get().getOperationName() != null) {
            WorkflowStepRun inFlightRow = priorRow.get();
            String operationName = inFlightRow.getOperationName();
            int remainingMinutes = remainingTimeoutMinutes(inFlightRow, timeoutMinutes);
            log.info("claude-code step {} resuming unconfirmed Cloud Run launch (operation {}, workerJobId={}, "
                    + "{}m remaining of {}m budget), skipping relaunch",
                    stepId, operationName, workerJobId, remainingMinutes, timeoutMinutes);
            appendLauncherLine(inFlightRow, projectId, logBuilder,
                    "→ Resuming check on unconfirmed Cloud Run launch: " + operationName);
            return pollUntilTerminal(target, null, operationName, runId, jobRun.getId(), workerJobId, stepDef,
                    remainingMinutes, logBuilder, inFlightRow, projectId);
        }

        Map<String, String> env;
        try {
            env = buildEnv(inv, stepDef, ctx, projectId, runId, jobRun, workerJobId,
                    timeoutMinutes, conductorMcp, conductorApiKey, oauthToken.get(), context.getConsumes(), ttlHours);
        } catch (CredentialResolutionException e) {
            log.warn("claude-code step {} credential resolution failed: {}", stepId, e.getMessage());
            return StepResult.failed("", "CLAUDE_CREDENTIAL_ERROR: " + e.getMessage()).withWorkerJobId(workerJobId);
        }
        ContainerTask task = new ContainerTask(image, CONTAINER_COMMAND, env, timeoutMinutes);

        appendLauncherLine(stepRun, projectId, logBuilder,
                "→ Launching Cloud Run execution (image=" + image + ", timeout=" + timeoutMinutes + "m)");

        CloudRunJobLauncher.LaunchResult launch;
        try {
            launch = launcher.startExecution(target, task);
        } catch (CloudRunJobLauncher.LaunchUnconfirmedException e) {
            // Genuinely inconclusive, not a confirmed failure (see the exception's javadoc): no
            // operationName was ever obtained, so unlike every other branch here there is nothing to
            // persist and poll/resume on. Surfaced as its own errorReason so a user isn't told the launch
            // definitively failed when the container may in fact be running to completion, orphaned.
            log.warn("Cloud Run did not acknowledge the RunJob request for claude-code step {}: {}", stepId, e.getMessage());
            appendLauncherLine(stepRun, projectId, logBuilder, "✗ " + e.getMessage());
            return StepResult.failed(logBuilder.toString(), "CLOUD_RUN_LAUNCH_UNCONFIRMED").withWorkerJobId(workerJobId);
        } catch (Exception e) {
            log.warn("Failed to start Cloud Run execution for claude-code step {}: {}", stepId, e.getMessage());
            appendLauncherLine(stepRun, projectId, logBuilder, "✗ " + e.getMessage());
            return StepResult.failed(logBuilder.toString(), "CLAUDE_LAUNCH_ERROR").withWorkerJobId(workerJobId);
        }

        // operationName is persisted unconditionally, before executionName is even known — the
        // durability point that lets a backend restart mid-launch resume checking instead of
        // relaunching a duplicate execution (see the operationName-only resume branch above).
        stepRun.setOperationName(launch.operationName());
        if (launch.executionName().isPresent()) {
            String executionName = launch.executionName().get();
            appendLauncherLine(stepRun, projectId, logBuilder, "← execution: " + executionName);
            stepRun.setExecutionName(executionName);
        } else {
            appendLauncherLine(stepRun, projectId, logBuilder,
                    "⚠ Cloud Run accepted the launch but hasn't confirmed the execution yet — will keep checking");
        }
        stepRunRepository.save(stepRun);

        return pollUntilTerminal(target, launch.executionName().orElse(null), launch.operationName(),
                runId, jobRun.getId(), workerJobId, stepDef, timeoutMinutes, logBuilder, stepRun, projectId);
    }

    /**
     * Appends one launcher-side status line both to the in-memory buffer that becomes
     * {@link StepResult#getLog()} and to the step row's persisted log (via
     * {@link WorkflowRunLogBroker#appendToStepLog}), so the run-detail UI reflects launcher progress
     * without waiting for the step to reach a terminal state. {@code stepRun} must be the same row
     * {@link WorkflowJobOrchestrator#persistStepRun} will later match by workerJobId — its log-clobber
     * guard relies on this row already carrying content by the time the terminal {@link StepResult} is
     * persisted, so every line added to {@code logBuilder} in this class must also flow through here.
     */
    private void appendLauncherLine(WorkflowStepRun stepRun, String projectId, StringBuilder logBuilder, String line) {
        logBuilder.append(line).append("\n");
        logBroker.appendToStepLog(stepRun, List.of(line), projectId);
    }

    /**
     * Looks up the pre-created row for this step by {@code (jobRunId, stepId)}. Reuses it (whatever
     * its status or stored execution name — resume path) if found; otherwise creates a new RUNNING
     * row with a fresh {@code workerJobId}, recording {@code stepType} as given by the caller.
     */
    private WorkflowStepRun resolveOrCreateStepRun(WorkflowJobRun jobRun, String stepId, Map<String, Object> stepDef,
                                                     String stepType) {
        Optional<WorkflowStepRun> existing = stepRunRepository.findByJobRunIdAndStepId(jobRun.getId(), stepId);
        if (existing.isPresent() && existing.get().getWorkerJobId() != null) {
            return existing.get();
        }

        WorkflowStepRun stepRun = existing.orElseGet(WorkflowStepRun::new);
        stepRun.setJobRun(jobRun);
        stepRun.setStepId(stepId);
        stepRun.setStepName((String) stepDef.getOrDefault("name", "unnamed"));
        stepRun.setStepType(stepType);
        stepRun.setStatus(WorkflowStepStatus.RUNNING);
        stepRun.setWorkerJobId(UUID.randomUUID().toString());
        stepRun.setStartedAt(OffsetDateTime.now());
        stepRunRepository.save(stepRun);
        return stepRun;
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

    /**
     * Bounded-iteration poll loop (not a wall-clock deadline) — matches {@link DockerStepExecutor}'s
     * shape so the timeout path is fast to unit test with {@link #sleepSeconds} overridden to a no-op.
     *
     * <p>{@code executionName} may be {@code null} on entry — meaning Cloud Run accepted the launch
     * (({@code operationName} known) but hasn't confirmed the execution yet (see
     * {@link CloudRunJobLauncher#startExecution}'s {@code LaunchResult}). In that phase, each tick both
     * checks whether the container already self-reported completion (the primary signal, keyed on
     * {@code workerJobId} alone — it doesn't need {@code executionName}) and tries
     * {@link CloudRunJobLauncher#tryResolveExecutionName} to resolve it; once resolved, the same loop
     * continues on into the normal Cloud-Run-execution-status polling below, sharing the remainder of
     * this one bounded budget rather than getting a fresh timeout window of its own.
     */
    private StepResult pollUntilTerminal(CloudRunTarget target, String executionName, String operationName,
                                          String runId, String jobRunId, String workerJobId,
                                          Map<String, Object> stepDef, int timeoutMinutes, StringBuilder logBuilder,
                                          WorkflowStepRun stepRun, String projectId) {
        // timeoutMinutes may already be <= 0 here (a resumed step whose budget elapsed while the
        // backend was down) -- 0 iterations falls straight through to the timeout tail below rather
        // than looping at least once, so a step that resumes past its deadline fails immediately
        // instead of getting a bonus poll.
        int maxIterations = timeoutMinutes <= 0 ? 0 : Math.max(1, (timeoutMinutes * 60) / POLL_INTERVAL_SECONDS);
        for (int i = 0; i < maxIterations; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

            if (runRepository.findStatusById(runId).orElse(null) == WorkflowRunStatus.CANCELLING) {
                if (executionName != null) {
                    launcher.cancelExecution(target, executionName);
                } else {
                    // Cloud Run accepted the launch but hasn't named the execution yet, so there's
                    // nothing to cancel by name. The execution it eventually starts is orphaned and
                    // runs to completion; Conductor still treats the step as cancelled.
                    log.warn("Run {} cancelled before its Cloud Run execution name resolved (operation {}) — "
                            + "the execution may run to completion orphaned", runId, operationName);
                }
                appendLauncherLine(stepRun, projectId, logBuilder, "✗ Cancelled");
                return StepResult.cancelled(logBuilder.toString()).withWorkerJobId(workerJobId);
            }

            if (executionName == null) {
                Optional<WorkflowStepRun> current = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRunId, workerJobId);
                if (current.isPresent() && isTerminal(current.get().getStatus())) {
                    log.info("claude-code step (workerJobId={}) self-reported terminal before its Cloud Run "
                            + "execution name resolved", workerJobId);
                    return resultFromRow(current.get(), stepDef, Optional.empty(), logBuilder.toString());
                }

                Optional<String> resolved;
                try {
                    resolved = launcher.tryResolveExecutionName(target, operationName);
                } catch (Exception e) {
                    log.warn("Error resolving Cloud Run execution name for operation {}: {}", operationName, e.getMessage());
                    continue;
                }
                if (resolved.isEmpty()) {
                    continue;
                }
                executionName = resolved.get();
                appendLauncherLine(stepRun, projectId, logBuilder, "← execution confirmed: " + executionName);
                stepRun.setExecutionName(executionName);
                stepRunRepository.save(stepRun);
                continue;
            }

            CloudRunJobLauncher.ExecutionState state;
            try {
                state = launcher.pollExecution(target, executionName);
            } catch (Exception e) {
                log.warn("Poll error for Cloud Run execution {}: {}", executionName, e.getMessage());
                continue;
            }

            if (state.status() != CloudRunJobLauncher.Status.RUNNING) {
                appendLauncherLine(stepRun, projectId, logBuilder, "← execution finished: " + state.status());
                return terminalResult(jobRunId, workerJobId, stepDef, state, logBuilder.toString());
            }
        }

        appendLauncherLine(stepRun, projectId, logBuilder, "✗ Timed out after " + timeoutMinutes + " minutes");
        if (executionName != null) {
            launcher.cancelExecution(target, executionName);
        }
        return StepResult.failed(logBuilder.toString(), "CLAUDE_TIMEOUT").withWorkerJobId(workerJobId);
    }

    private StepResult terminalResult(String jobRunId, String workerJobId, Map<String, Object> stepDef,
                                       CloudRunJobLauncher.ExecutionState cloudRunState, String log) {
        Optional<WorkflowStepRun> rowOpt = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRunId, workerJobId);
        if (rowOpt.isPresent() && isTerminal(rowOpt.get().getStatus())) {
            return resultFromRow(rowOpt.get(), stepDef, cloudRunState.exitCode(), log);
        }

        // The container never got to self-report (OOM, image pull failure, task killed by Cloud
        // Run) — fall back entirely to what Cloud Run itself observed.
        if (cloudRunState.status() == CloudRunJobLauncher.Status.SUCCEEDED) {
            return StepResult.success(log, Map.of()).withWorkerJobId(workerJobId);
        }
        String errorReason = cloudRunState.exitCode().map(this::mapExitCode).orElse("CLAUDE_LAUNCH_ERROR");
        return StepResult.failed(log, errorReason).withWorkerJobId(workerJobId);
    }

    private StepResult resultFromRow(WorkflowStepRun row, Map<String, Object> stepDef,
                                      Optional<Integer> exitCodeFallback, String log) {
        String workerJobId = row.getWorkerJobId();
        if (row.getStatus() == WorkflowStepStatus.SUCCESS) {
            Map<String, String> outputs = row.getOutputJson() != null
                    ? parseOutputs(row.getOutputJson())
                    : new HashMap<>();
            StepOutputMapper.applyDeclaredOutputs(stepDef,
                    StepOutputMapper.outputsTree(objectMapper, outputs), outputs);
            return StepResult.success(log, outputs).withWorkerJobId(workerJobId);
        }

        String errorReason = row.getErrorReason() != null
                ? row.getErrorReason()
                : exitCodeFallback.map(this::mapExitCode).orElse("CLAUDE_LAUNCH_ERROR");
        return StepResult.failed(log, errorReason).withWorkerJobId(workerJobId);
    }

    /** Container exit-code → errorReason fallback, used only when the container never self-reported one. */
    private String mapExitCode(int exitCode) {
        return switch (exitCode) {
            case 10 -> "CLAUDE_AGENT_ERROR";
            case 11 -> "CLAUDE_AUTH_ERROR";
            case 12 -> "CLAUDE_RATE_LIMITED";
            case 13 -> "CLAUDE_TIMEOUT";
            case 20 -> "CLAUDE_CONFIG_ERROR";
            default -> "CLAUDE_LAUNCH_ERROR";
        };
    }

    private boolean isTerminal(WorkflowStepStatus status) {
        return status == WorkflowStepStatus.SUCCESS || status == WorkflowStepStatus.FAILED;
    }

    private Map<String, String> parseOutputs(String outputJson) {
        try {
            return objectMapper.readValue(outputJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse step outputs JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private int loadTokenTtlHours(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getRunTokenTtlHours)
                .orElse(24);
    }

    /**
     * Minutes left in {@code fullTimeoutMinutes}' budget, measured from {@code stepRun.getStartedAt()}
     * (set once, at first launch, and never touched again by {@link #resolveOrCreateStepRun} on
     * resume) rather than from now. Without this, a step resumed after a backend restart --
     * {@link #run}'s two resume branches, which skip relaunching and poll the existing execution --
     * would hand {@link #pollUntilTerminal} a fresh full budget every time, so a step that resumes
     * repeatedly (e.g. across several redeploys) could run for a multiple of its declared timeout
     * before ever actually being cancelled. A missing {@code startedAt} (shouldn't happen in
     * production -- every row is created through {@link #resolveOrCreateStepRun} -- but keeps this
     * defensive rather than crashing) falls back to the full budget.
     */
    private int remainingTimeoutMinutes(WorkflowStepRun stepRun, int fullTimeoutMinutes) {
        if (stepRun.getStartedAt() == null) {
            return fullTimeoutMinutes;
        }
        long elapsedMinutes = Duration.between(stepRun.getStartedAt(), OffsetDateTime.now()).toMinutes();
        return (int) Math.max(0, fullTimeoutMinutes - elapsedMinutes);
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

    protected void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
