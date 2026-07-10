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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Executes {@code uses: claude-code} workflow steps on {@code runs-on: cloud-run} jobs — launches a
 * pre-created Cloud Run Job ({@link CloudRunJobLauncher}) with the container env contract, polls to
 * completion, and reads the result from the {@link WorkflowStepRun} row the container self-reported
 * into via the {@code /internal/v1} step-complete callback (Phase 2). Self-hosted {@code claude-code}
 * steps never reach this class — {@link WorkflowJobOrchestrator} routes {@code runs-on: self-hosted}
 * jobs to the daemon before entering the step loop; this executor defensively rejects anything other
 * than {@code cloud-run} via {@link StepExecutionContext#getRunsOn()}.
 *
 * <h2>Credentials</h2>
 * The Anthropic API key comes from the project's {@code claude} {@link ProviderCredentialService}
 * credential (the same BYO-key store the {@code agent} step uses). When {@code conductor_mcp: true},
 * the container also needs a Conductor project API key for its MCP server — {@link ProjectApiKey}'s
 * {@code key_value} column stores the raw key in plaintext (it's looked up by raw value for API-key
 * auth, see {@code ProjectApiKeyRepository#findByKeyValueWithProject}), so it's recovered directly via
 * {@link ProjectApiKeyRepository} rather than a second {@code ProviderCredential} entry. When a
 * project has multiple non-revoked keys, the first one returned is used — there is no "the" key for
 * automation today; a future refinement could let a step reference one by name.
 *
 * <h2>Crash recovery (partial)</h2>
 * The Cloud Run execution resource name is kept in memory only, not persisted — if this backend
 * instance dies mid-poll and the job is re-dispatched, a fresh {@code execute()} call looks up the
 * pre-created row by {@code (jobRunId, stepId)}:
 * <ul>
 *   <li><b>Covered:</b> if the container already self-reported (row status is terminal), the result
 *       is read straight from the row — no relaunch, no duplicate execution.</li>
 *   <li><b>Not covered:</b> if the row is still {@code PENDING}/{@code RUNNING}, the execution name is
 *       gone, so a new Cloud Run execution is launched under the <i>same</i> {@code workerJobId} (to
 *       avoid a duplicate row). The orphaned original execution keeps running; if it later completes
 *       and posts its own step-complete callback, it can race and overwrite whatever the new
 *       execution already persisted for that row. This mirrors the plan's accepted top risk #4 — a
 *       durable execution-name column was considered and rejected for this phase (the entity's
 *       existing {@code image} column is semantically "container image" elsewhere and repurposing it
 *       would mislead anyone reading step rows in the UI); closing the gap needs a schema change,
 *       left for a follow-up.</li>
 * </ul>
 */
@Component
public class ClaudeCodeStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeStepExecutor.class);

    private static final String STEP_TYPE = "claude-code";
    private static final String CLOUD_RUN = "cloud-run";
    private static final String CLAUDE_PROVIDER = "claude";
    private static final int DEFAULT_TIMEOUT_MINUTES = 30;
    private static final int MAX_TIMEOUT_MINUTES = 120;
    private static final int POLL_INTERVAL_SECONDS = 10;

    private final CloudRunJobLauncher launcher;
    private final ProviderCredentialService credentialService;
    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final ObjectMapper objectMapper;
    private final String backendBaseUrl;

    public ClaudeCodeStepExecutor(CloudRunJobLauncher launcher,
                                   ProviderCredentialService credentialService,
                                   ProjectApiKeyRepository projectApiKeyRepository,
                                   WorkflowStepRunRepository stepRunRepository,
                                   RunTokenService runTokenService,
                                   ProjectSettingsRepository projectSettingsRepository,
                                   WorkflowInterpolator interpolator,
                                   ObjectMapper objectMapper,
                                   @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.launcher = launcher;
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
        if (!CLOUD_RUN.equals(context.getRunsOn())) {
            return StepResult.failed("", "CLAUDE_INVALID_RUNS_ON: claude-code steps require the job to "
                    + "declare 'runs-on: cloud-run' (or 'runs-on: self-hosted', dispatched separately "
                    + "to the daemon). Got: " + context.getRunsOn());
        }

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

        Optional<String> apiKey = credentialService.resolveApiKey(projectId, CLAUDE_PROVIDER);
        if (apiKey.isEmpty()) {
            return StepResult.failed("", "CLAUDE_CREDENTIAL_MISSING: no Claude API key configured for this project");
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

        String workerJobId = resolveOrCreateStepRun(jobRun, stepId, stepDef);
        Optional<StepResult> alreadyTerminal = readTerminalResultIfPresent(jobRun.getId(), workerJobId, stepDef);
        if (alreadyTerminal.isPresent()) {
            log.info("claude-code step {} already terminal on resume (workerJobId={}), skipping relaunch",
                    stepId, workerJobId);
            return alreadyTerminal.get();
        }

        int timeoutMinutes = resolveTimeoutMinutes(stepDef);
        Map<String, String> env = buildEnv(stepDef, ctx, prompt, projectId, runId, jobRun, workerJobId,
                timeoutMinutes, conductorMcp, conductorApiKey, apiKey.get());

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("→ Launching Cloud Run execution (timeout=").append(timeoutMinutes).append("m)\n");

        String executionName;
        try {
            executionName = launcher.startExecution(env, timeoutMinutes);
            logBuilder.append("← execution: ").append(executionName).append("\n");
        } catch (Exception e) {
            log.warn("Failed to start Cloud Run execution for claude-code step {}: {}", stepId, e.getMessage());
            logBuilder.append("✗ ").append(e.getMessage()).append("\n");
            return StepResult.failed(logBuilder.toString(), "CLAUDE_LAUNCH_ERROR").withWorkerJobId(workerJobId);
        }

        return pollUntilTerminal(executionName, jobRun.getId(), workerJobId, stepDef, timeoutMinutes, logBuilder);
    }

    /**
     * Looks up the pre-created row for this step by {@code (jobRunId, stepId)}. Reuses its
     * {@code workerJobId} if found (whatever its status — resume path); otherwise creates a new
     * RUNNING row with a fresh {@code workerJobId}.
     */
    private String resolveOrCreateStepRun(WorkflowJobRun jobRun, String stepId, Map<String, Object> stepDef) {
        Optional<WorkflowStepRun> existing = stepRunRepository.findByJobRunIdAndStepId(jobRun.getId(), stepId);
        if (existing.isPresent() && existing.get().getWorkerJobId() != null) {
            return existing.get().getWorkerJobId();
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
        return stepRun.getWorkerJobId();
    }

    /** If the pre-created row is already terminal (container self-reported before a restart), builds the result from it directly. */
    private Optional<StepResult> readTerminalResultIfPresent(String jobRunId, String workerJobId, Map<String, Object> stepDef) {
        Optional<WorkflowStepRun> rowOpt = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRunId, workerJobId);
        if (rowOpt.isEmpty() || !isTerminal(rowOpt.get().getStatus())) {
            return Optional.empty();
        }
        return Optional.of(resultFromRow(rowOpt.get(), stepDef, Optional.empty(), "Resumed from prior run\n"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildEnv(Map<String, Object> stepDef, RuntimeContext ctx, String prompt,
                                          String projectId, String runId, WorkflowJobRun jobRun, String workerJobId,
                                          int timeoutMinutes, boolean conductorMcp, String conductorApiKey,
                                          String anthropicApiKey) {
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
        // Cloud Run always uses API-key billing; CLAUDE_CODE_OAUTH_TOKEN is a self-hosted-only concept
        // and must never be set here (that path never reaches this executor).
        env.put("ANTHROPIC_API_KEY", anthropicApiKey);
        return env;
    }

    /**
     * Bounded-iteration poll loop (not a wall-clock deadline) — matches {@link DockerStepExecutor}'s
     * shape so the timeout path is fast to unit test with {@link #sleepSeconds} overridden to a no-op.
     */
    private StepResult pollUntilTerminal(String executionName, String jobRunId, String workerJobId,
                                          Map<String, Object> stepDef, int timeoutMinutes, StringBuilder logBuilder) {
        int maxIterations = Math.max(1, (timeoutMinutes * 60) / POLL_INTERVAL_SECONDS);
        for (int i = 0; i < maxIterations; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

            CloudRunJobLauncher.ExecutionState state;
            try {
                state = launcher.pollExecution(executionName);
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
        launcher.cancelExecution(executionName);
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
            ObjectNode body = objectMapper.valueToTree(outputs);
            StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);
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
