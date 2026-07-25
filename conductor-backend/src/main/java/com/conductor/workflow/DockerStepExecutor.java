package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class DockerStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerStepExecutor.class);

    private static final String DEFAULT_IMAGE = RunnerImage.DEFAULT;
    private static final String DOCKER_USES_PREFIX = "docker://";
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_TIMEOUT_MINUTES = 5;
    private static final int MAX_TIMEOUT_MINUTES = 120;

    private final WorkerVmClient workerVmClient;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowInterpolator interpolator;
    private final String backendBaseUrl;

    public DockerStepExecutor(
            WorkerVmClient workerVmClient,
            RunTokenService runTokenService,
            ProjectSettingsRepository projectSettingsRepository,
            WorkflowRunRepository runRepository,
            WorkflowInterpolator interpolator,
            @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.workerVmClient = workerVmClient;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.runRepository = runRepository;
        this.interpolator = interpolator;
        this.backendBaseUrl = backendBaseUrl;
    }

    @Override
    public String getStepType() {
        return "docker";
    }

    @Override
    public StepResult execute(StepExecutionContext context) {
        Map<String, Object> stepDef = context.getStepDefinition();
        RuntimeContext ctx = context.getRuntimeContext();
        String runId = context.getRun().getId();
        String jobId = context.getJobRun().getJobId();

        String image = resolveImage(stepDef);
        int ttlHours = loadTokenTtlHours(context.getProjectId());
        String ephemeralToken = runTokenService.generateRunToken(runId, ttlHours);
        Map<String, String> env = interpolateEnv(stepDef, ctx);

        String logCallbackUrl = backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/log-chunk";
        String outputsCallbackUrl = backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/outputs";
        String jobFailedCallbackUrl = backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/job-failed";

        WorkerVmClient.RunJobRequest request = new WorkerVmClient.RunJobRequest(
                runId, jobId, image, env,
                logCallbackUrl, outputsCallbackUrl, jobFailedCallbackUrl,
                ephemeralToken
        );

        String workerJobId;
        try {
            workerJobId = workerVmClient.submitJob(request);
        } catch (WorkerVmClient.WorkerUnavailableException e) {
            return StepResult.failed("Worker unavailable after retries\n", "Worker unavailable: " + e.getMessage());
        } catch (WorkerVmClient.WorkerCommunicationException e) {
            return StepResult.failed("Failed to contact worker\n", "Worker error: " + e.getMessage());
        }

        log.info("Submitted docker job: workerJobId={}, runId={}, jobId={}, image={}", workerJobId, runId, jobId, image);

        int timeoutMinutes = resolveTimeoutMinutes(stepDef);
        return pollForCompletion(runId, workerJobId, image, timeoutMinutes);
    }

    private StepResult pollForCompletion(String runId, String workerJobId, String image, int timeoutMinutes) {
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("Running docker image: ").append(image).append("\n");

        int maxPollIterations = (timeoutMinutes * 60) / POLL_INTERVAL_SECONDS;
        for (int i = 0; i < maxPollIterations; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

            if (runRepository.findStatusById(runId).orElse(null) == WorkflowRunStatus.CANCELLING) {
                try {
                    workerVmClient.cancelJob(workerJobId);
                } catch (WorkerVmClient.WorkerCommunicationException e) {
                    log.warn("Failed to cancel worker job {}: {}", workerJobId, e.getMessage());
                }
                logBuilder.append("Job cancelled\n");
                return StepResult.cancelled(logBuilder.toString());
            }

            WorkerVmClient.WorkerJobStatus status;
            try {
                status = workerVmClient.getJobStatus(workerJobId);
            } catch (WorkerVmClient.WorkerCommunicationException e) {
                log.warn("Failed to poll job status for workerJobId={}: {}", workerJobId, e.getMessage());
                continue;
            }

            if (status.isTerminal()) {
                logBuilder.append("Job completed with status: ").append(status.status()).append("\n");
                if ("SUCCESS".equals(status.status())) {
                    return StepResult.success(logBuilder.toString(), Map.of());
                } else {
                    String exitCodeMsg = status.exitCode() != null
                            ? "Exit code: " + status.exitCode()
                            : "Job failed";
                    logBuilder.append(exitCodeMsg).append("\n");
                    return StepResult.failed(logBuilder.toString(), exitCodeMsg);
                }
            }
        }

        logBuilder.append("Job timed out after ").append(maxPollIterations * POLL_INTERVAL_SECONDS).append("s\n");
        return StepResult.failed(logBuilder.toString(), "Docker job timed out");
    }

    private int resolveTimeoutMinutes(Map<String, Object> stepDef) {
        int timeoutMinutes = getIntOrDefault(stepDef, "timeout_minutes", DEFAULT_TIMEOUT_MINUTES);
        return Math.min(Math.max(timeoutMinutes, 1), MAX_TIMEOUT_MINUTES);
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }

    private String resolveImage(Map<String, Object> stepDef) {
        Object usesVal = stepDef.get("uses");
        if (usesVal instanceof String uses && uses.startsWith(DOCKER_USES_PREFIX)) {
            String image = uses.substring(DOCKER_USES_PREFIX.length()).trim();
            return image.isEmpty() ? DEFAULT_IMAGE : image;
        }
        return DEFAULT_IMAGE;
    }

    private int loadTokenTtlHours(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getRunTokenTtlHours)
                .orElse(24);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> interpolateEnv(Map<String, Object> stepDef, RuntimeContext ctx) {
        Map<String, String> result = new HashMap<>();
        Object envObj = stepDef.get("env");
        if (!(envObj instanceof Map)) return result;

        Map<String, Object> envMap = (Map<String, Object>) envObj;
        for (Map.Entry<String, Object> entry : envMap.entrySet()) {
            String value = entry.getValue() != null
                    ? interpolator.interpolate(entry.getValue().toString(), ctx)
                    : "";
            result.put(entry.getKey(), value);
        }
        return result;
    }

    protected void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
