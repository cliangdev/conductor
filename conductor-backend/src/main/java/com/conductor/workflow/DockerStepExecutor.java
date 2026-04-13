package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.repository.ProjectSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class DockerStepExecutor implements WorkflowExecutionBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerStepExecutor.class);

    private static final String DEFAULT_IMAGE = "ghcr.io/cliangdev/conductor-runner:2";
    private static final String DOCKER_USES_PREFIX = "docker://";
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int MAX_POLL_ITERATIONS = 60;

    private final WorkerVmClient workerVmClient;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final WorkflowInterpolator interpolator;
    private final String backendBaseUrl;

    public DockerStepExecutor(
            WorkerVmClient workerVmClient,
            RunTokenService runTokenService,
            ProjectSettingsRepository projectSettingsRepository,
            WorkflowInterpolator interpolator,
            @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.workerVmClient = workerVmClient;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
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

        String logCallbackUrl = backendBaseUrl + "/internal/workflow-runs/" + runId + "/log-chunk";
        String outputsCallbackUrl = backendBaseUrl + "/internal/workflow-runs/" + runId + "/outputs";
        String jobFailedCallbackUrl = backendBaseUrl + "/internal/workflow-runs/" + runId + "/job-failed";

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

        return pollForCompletion(workerJobId, image);
    }

    private StepResult pollForCompletion(String workerJobId, String image) {
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("Running docker image: ").append(image).append("\n");

        for (int i = 0; i < MAX_POLL_ITERATIONS; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

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

        logBuilder.append("Job timed out after ").append(MAX_POLL_ITERATIONS * POLL_INTERVAL_SECONDS).append("s\n");
        return StepResult.failed(logBuilder.toString(), "Docker job timed out");
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
