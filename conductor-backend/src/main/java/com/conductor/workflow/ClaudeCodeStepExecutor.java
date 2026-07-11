package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes {@code uses: claude-code} workflow steps by launching a Cloud Run Job execution
 * ({@link CloudRunJobLauncher}) against a target resolved by {@link RuntimeTargetResolver}, polling
 * to completion, and reading the result from the {@link WorkflowStepRun} row the container
 * self-reported into via the {@code /internal/v1} step-complete callback. Self-hosted
 * {@code claude-code} steps never reach this class — {@link WorkflowJobOrchestrator} routes
 * {@code runs-on: self-hosted} jobs to the daemon before entering the step loop; this executor
 * rejects anything the resolver doesn't resolve ({@code runs-on: cloud-run} or a named, ACTIVE
 * project runtime target).
 *
 * <h2>Credentials</h2>
 * {@code claude-code} steps are subscription-auth only, on every runtime: the container is the
 * subscription runtime, and the {@link ProviderCredentialService} credential stored under provider
 * id {@code claude-code} — a Claude Code OAuth token from {@code claude setup-token}, distinct from
 * the {@code claude} provider the {@code agent} step's Anthropic API key lives under — is injected
 * as {@code CLAUDE_CODE_OAUTH_TOKEN}. {@code ANTHROPIC_API_KEY} is never set by this class; API-key
 * users should use the {@code agent} step instead. When {@code conductor_mcp: true},
 * the container also needs a Conductor project API key for its MCP server — {@link ProjectApiKey}'s
 * {@code key_value} column stores the raw key in plaintext (it's looked up by raw value for API-key
 * auth, see {@code ProjectApiKeyRepository#findByKeyValueWithProject}), so it's recovered directly via
 * {@link ProjectApiKeyRepository} rather than a second {@code ProviderCredential} entry. When a
 * project has multiple non-revoked keys, the first one returned is used — there is no "the" key for
 * automation today; a future refinement could let a step reference one by name.
 *
 * <h2>Crash recovery</h2>
 * The Cloud Run execution resource name is persisted onto the step-run row right after
 * {@link CloudRunJobLauncher#startExecution} returns, so a backend restart can re-attach instead of
 * relaunching a duplicate execution. A fresh {@code execute()} call looks up the pre-created row by
 * {@code (jobRunId, stepId)}:
 * <ul>
 *   <li>if the container already self-reported (row status is terminal), the result is read straight
 *       from the row — no relaunch, no duplicate execution.</li>
 *   <li>if the row is still {@code PENDING}/{@code RUNNING} but already carries a stored execution
 *       name, polling resumes against that same Cloud Run execution — again no relaunch.</li>
 *   <li>only when neither applies (a brand new row, or an existing row with no execution name yet) is
 *       a new Cloud Run execution launched.</li>
 * </ul>
 */
@Component
public class ClaudeCodeStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeStepExecutor.class);

    private static final String STEP_TYPE = "claude-code";
    /** Distinct from {@code "claude"}, the {@code agent} step's Anthropic API-key provider. */
    private static final String CLAUDE_CODE_PROVIDER = "claude-code";
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final int POLL_INTERVAL_SECONDS = 10;
    private static final List<String> CONTAINER_COMMAND = List.of("conductor-claude-entrypoint");

    private final CloudRunJobLauncher launcher;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final ProviderCredentialService credentialService;
    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;
    private final String backendBaseUrl;

    public ClaudeCodeStepExecutor(CloudRunJobLauncher launcher,
                                   RuntimeTargetResolver runtimeTargetResolver,
                                   ProviderCredentialService credentialService,
                                   ProjectApiKeyRepository projectApiKeyRepository,
                                   WorkflowStepRunRepository stepRunRepository,
                                   RunTokenService runTokenService,
                                   ProjectSettingsRepository projectSettingsRepository,
                                   WorkflowInterpolator interpolator,
                                   ObjectMapper objectMapper,
                                   @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.launcher = launcher;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.credentialService = credentialService;
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.stepRunRepository = stepRunRepository;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.interpolator = interpolator;
        this.objectMapper = objectMapper;
        this.backendBaseUrl = backendBaseUrl;
    }

    @Override
    public String getStepType() { return STEP_TYPE; }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved;
        try {
            resolved = runtimeTargetResolver.resolve(context.getProjectId(), context.getRunsOn());
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

        Object promptObj = stepDef.get("prompt");
        if (promptObj == null || promptObj.toString().isBlank()) {
            return StepResult.failed("", "Step 'prompt' is required for claude-code step");
        }
        String prompt = interpolator.interpolate(promptObj.toString(), ctx);

        Optional<String> oauthToken = credentialService.resolveApiKey(projectId, CLAUDE_CODE_PROVIDER);
        if (oauthToken.isEmpty()) {
            return StepResult.failed("", "CLAUDE_SUBSCRIPTION_NOT_CONFIGURED: no Claude Code subscription "
                    + "token configured for this project. Run 'claude setup-token' and store the result as "
                    + "the project's Claude Code credential under Agents → Providers.");
        }

        boolean conductorMcp = getBooleanOrDefault(stepDef, "conductor_mcp", false);
        String conductorApiKey = null;
        if (conductorMcp) {
            List<ProjectApiKey> keys = projectApiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId);
            if (keys.isEmpty()) {
                return StepResult.failed("", "PROJECT_API_KEY_MISSING: conductor_mcp requires a project "
                        + "API key (create one in project settings)");
            }
            conductorApiKey = keys.get(0).getKeyValue();
        }

        WorkflowStepRun stepRun = resolveOrCreateStepRun(jobRun, stepId, stepDef);
        String workerJobId = stepRun.getWorkerJobId();

        Optional<WorkflowStepRun> priorRow = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRun.getId(), workerJobId);
        if (priorRow.isPresent() && isTerminal(priorRow.get().getStatus())) {
            log.info("claude-code step {} already terminal on resume (workerJobId={}), skipping relaunch",
                    stepId, workerJobId);
            return resultFromRow(priorRow.get(), stepDef, Optional.empty(), "Resumed from prior run\n");
        }

        int timeoutMinutes = resolveTimeoutMinutes(stepDef);
        StringBuilder logBuilder = new StringBuilder();

        if (priorRow.isPresent() && priorRow.get().getExecutionName() != null) {
            String executionName = priorRow.get().getExecutionName();
            log.info("claude-code step {} resuming poll on prior Cloud Run execution {} (workerJobId={}), "
                    + "skipping relaunch", stepId, executionName, workerJobId);
            logBuilder.append("→ Resuming poll on prior Cloud Run execution: ").append(executionName).append("\n");
            return pollUntilTerminal(target, executionName, jobRun.getId(), workerJobId, stepDef, timeoutMinutes, logBuilder);
        }

        Map<String, String> env = buildEnv(stepDef, ctx, prompt, projectId, runId, jobRun, workerJobId,
                timeoutMinutes, conductorMcp, conductorApiKey, oauthToken.get());
        ContainerTask task = new ContainerTask(image, CONTAINER_COMMAND, env, timeoutMinutes);

        logBuilder.append("→ Launching Cloud Run execution (timeout=").append(timeoutMinutes).append("m)\n");

        String executionName;
        try {
            executionName = launcher.startExecution(target, task);
            logBuilder.append("← execution: ").append(executionName).append("\n");
        } catch (Exception e) {
            log.warn("Failed to start Cloud Run execution for claude-code step {}: {}", stepId, e.getMessage());
            logBuilder.append("✗ ").append(e.getMessage()).append("\n");
            return StepResult.failed(logBuilder.toString(), "CLAUDE_LAUNCH_ERROR").withWorkerJobId(workerJobId);
        }

        stepRun.setExecutionName(executionName);
        stepRunRepository.save(stepRun);

        return pollUntilTerminal(target, executionName, jobRun.getId(), workerJobId, stepDef, timeoutMinutes, logBuilder);
    }

    /**
     * Looks up the pre-created row for this step by {@code (jobRunId, stepId)}. Reuses it (whatever
     * its status or stored execution name — resume path) if found; otherwise creates a new RUNNING
     * row with a fresh {@code workerJobId}.
     */
    private WorkflowStepRun resolveOrCreateStepRun(WorkflowJobRun jobRun, String stepId, Map<String, Object> stepDef) {
        Optional<WorkflowStepRun> existing = stepRunRepository.findByJobRunIdAndStepId(jobRun.getId(), stepId);
        if (existing.isPresent() && existing.get().getWorkerJobId() != null) {
            return existing.get();
        }

        WorkflowStepRun stepRun = existing.orElseGet(WorkflowStepRun::new);
        stepRun.setJobRun(jobRun);
        stepRun.setStepId(stepId);
        stepRun.setStepName((String) stepDef.getOrDefault("name", "unnamed"));
        stepRun.setStepType(STEP_TYPE);
        stepRun.setStatus(WorkflowStepStatus.RUNNING);
        stepRun.setWorkerJobId(UUID.randomUUID().toString());
        stepRun.setStartedAt(OffsetDateTime.now());
        stepRunRepository.save(stepRun);
        return stepRun;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildEnv(Map<String, Object> stepDef, RuntimeContext ctx, String prompt,
                                          String projectId, String runId, WorkflowJobRun jobRun, String workerJobId,
                                          int timeoutMinutes, boolean conductorMcp, String conductorApiKey,
                                          String oauthToken) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CONDUCTOR_STEP_PROMPT", prompt);

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

        Object allowedTools = stepDef.get("allowed_tools");
        if (allowedTools != null) {
            env.put("CONDUCTOR_ALLOWED_TOOLS", allowedTools.toString());
        }
        Object maxTurns = stepDef.get("max_turns");
        if (maxTurns instanceof Number n) {
            env.put("CONDUCTOR_MAX_TURNS", String.valueOf(n.intValue()));
        }
        Object outputSchema = stepDef.get("output_schema");
        if (outputSchema instanceof Map) {
            env.put("CONDUCTOR_OUTPUT_SCHEMA_JSON", toJson(outputSchema));
        }

        env.put("CONDUCTOR_TIMEOUT_MINUTES", String.valueOf(timeoutMinutes));
        env.put("CONDUCTOR_MCP_ENABLED", String.valueOf(conductorMcp));
        env.put("CONDUCTOR_API_URL", backendBaseUrl);
        env.put("CONDUCTOR_PROJECT_ID", projectId);
        env.put("CONDUCTOR_WORKFLOW_RUN_ID", runId);
        env.put("CONDUCTOR_JOB_ID", jobRun.getJobId());
        env.put("CONDUCTOR_WORKER_JOB_ID", workerJobId);

        int ttlHours = loadTokenTtlHours(projectId);
        env.put("CONDUCTOR_RUN_TOKEN", runTokenService.generateRunToken(runId, ttlHours));
        env.put("CONDUCTOR_LOG_CHUNK_URL", backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/log-chunk");
        env.put("CONDUCTOR_STEP_COMPLETE_URL",
                backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/steps/" + workerJobId + "/complete");

        if (conductorMcp) {
            env.put("CONDUCTOR_API_KEY", conductorApiKey);
        }
        // Subscription auth only, on every runtime this executor launches — never ANTHROPIC_API_KEY.
        env.put("CLAUDE_CODE_OAUTH_TOKEN", oauthToken);
        return env;
    }

    /**
     * Bounded-iteration poll loop (not a wall-clock deadline) — matches {@link DockerStepExecutor}'s
     * shape so the timeout path is fast to unit test with {@link #sleepSeconds} overridden to a no-op.
     */
    private StepResult pollUntilTerminal(CloudRunTarget target, String executionName, String jobRunId, String workerJobId,
                                          Map<String, Object> stepDef, int timeoutMinutes, StringBuilder logBuilder) {
        int maxIterations = Math.max(1, (timeoutMinutes * 60) / POLL_INTERVAL_SECONDS);
        for (int i = 0; i < maxIterations; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

            CloudRunJobLauncher.ExecutionState state;
            try {
                state = launcher.pollExecution(target, executionName);
            } catch (Exception e) {
                log.warn("Poll error for Cloud Run execution {}: {}", executionName, e.getMessage());
                continue;
            }

            if (state.status() != CloudRunJobLauncher.Status.RUNNING) {
                logBuilder.append("← execution finished: ").append(state.status()).append("\n");
                return terminalResult(jobRunId, workerJobId, stepDef, state, logBuilder.toString());
            }
        }

        logBuilder.append("✗ Timed out after ").append(timeoutMinutes).append(" minutes\n");
        launcher.cancelExecution(target, executionName);
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

    private int resolveTimeoutMinutes(Map<String, Object> stepDef) {
        int minutes = getIntOrDefault(stepDef, "timeout_minutes", DEFAULT_TIMEOUT_MINUTES);
        if (minutes < 1) return 1;
        if (minutes > MAX_TIMEOUT_MINUTES) return MAX_TIMEOUT_MINUTES;
        return minutes;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
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
