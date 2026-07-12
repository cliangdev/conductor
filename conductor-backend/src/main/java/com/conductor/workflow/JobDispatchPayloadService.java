package com.conductor.workflow;

import com.conductor.entity.ProjectSettings;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.exception.ConflictException;
import com.conductor.generated.model.JobDispatchCallbacksDto;
import com.conductor.generated.model.JobDispatchPayloadDto;
import com.conductor.generated.model.JobDispatchStepDto;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the interpolated dispatch payload the daemon fetches at job pickup (env, per-step prompt/
 * inputs, a freshly-minted run token). Never persisted, never logged — mirrors {@code needs}/secrets
 * interpolation the same way {@link com.conductor.workflow.WorkflowJobOrchestrator#planJobExecution}
 * does for conductor-hosted jobs.
 */
@Component
public class JobDispatchPayloadService {

    private static final String DOCKER_USES_PREFIX = "docker://";

    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final RuntimeContextBuilder contextBuilder;
    private final WorkflowInterpolator interpolator;
    private final RunTokenService runTokenService;
    private final ProjectSettingsRepository projectSettingsRepository;
    private final ObjectMapper objectMapper;
    private final UpstreamOutputsResolver upstreamOutputsResolver;
    private final WorkflowYamlParser yamlParser;
    private final String backendBaseUrl;

    public JobDispatchPayloadService(WorkflowRunRepository runRepository,
                                      WorkflowJobRunRepository jobRunRepository,
                                      WorkflowStepRunRepository stepRunRepository,
                                      RuntimeContextBuilder contextBuilder,
                                      WorkflowInterpolator interpolator,
                                      RunTokenService runTokenService,
                                      ProjectSettingsRepository projectSettingsRepository,
                                      ObjectMapper objectMapper,
                                      UpstreamOutputsResolver upstreamOutputsResolver,
                                      @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl,
                                      WorkflowYamlParser yamlParser) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.contextBuilder = contextBuilder;
        this.interpolator = interpolator;
        this.runTokenService = runTokenService;
        this.projectSettingsRepository = projectSettingsRepository;
        this.objectMapper = objectMapper;
        this.upstreamOutputsResolver = upstreamOutputsResolver;
        this.backendBaseUrl = backendBaseUrl;
        this.yamlParser = yamlParser;
    }

    @Transactional(readOnly = true)
    public JobDispatchPayloadDto buildPayload(String runId, String jobId) {
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(runId, jobId);
        if (jobRuns.isEmpty()) {
            throw new EntityNotFoundException("Job not found: " + jobId);
        }
        WorkflowJobRun jobRun = jobRuns.get(0);
        if (jobRun.getStatus() != WorkflowJobStatus.AWAITING_PICKUP) {
            throw new ConflictException("Job " + jobId + " is not awaiting pickup (status=" + jobRun.getStatus() + ")");
        }

        WorkflowDefinition workflow = run.getWorkflow();
        WorkflowSpec parsedWorkflow = parseYaml(workflow.getYaml());
        Map<String, JobSpec> jobs = parsedWorkflow != null ? parsedWorkflow.jobs() : null;
        if (jobs == null || !jobs.containsKey(jobId)) {
            throw new EntityNotFoundException("Job definition not found in workflow YAML: " + jobId);
        }
        JobSpec jobDef = jobs.get(jobId);

        String projectId = workflow.getProject().getId();
        Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
        Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);
        RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs, jobRun.getIteration());

        Map<String, String> jobEnv = jobDef.raw().get("env") instanceof Map
                ? interpolateStringMap(castStringObjectMap(jobDef.raw().get("env")), ctx)
                : new HashMap<>();

        List<StepSpec> steps = jobDef.executableSteps();
        List<JobDispatchStepDto> stepDtos = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            stepDtos.add(buildStepDto(jobRun.getId(), i, steps.get(i), ctx));
        }

        int ttlHours = loadTokenTtlHours(projectId);
        String runToken = runTokenService.generateRunToken(runId, ttlHours);

        JobDispatchCallbacksDto callbacks = new JobDispatchCallbacksDto();
        callbacks.setLogChunkUrlTemplate(backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/log-chunk");
        callbacks.setStepCompleteUrlTemplate(
                backendBaseUrl + "/internal/v1/workflow-runs/" + runId + "/steps/{workerJobId}/complete");

        JobDispatchPayloadDto dto = new JobDispatchPayloadDto();
        dto.setJobRunId(jobRun.getId());
        dto.setProtocol(2);
        dto.setImage(resolveJobImage(jobDef, steps));
        dto.setEnv(jobEnv);
        dto.setSteps(stepDtos);
        dto.setRunToken(runToken);
        dto.setCallbacks(callbacks);
        return dto;
    }

    private JobDispatchStepDto buildStepDto(String jobRunId, int index, StepSpec stepDef, RuntimeContext ctx) {
        JobDispatchStepDto dto = new JobDispatchStepDto();
        dto.setStepIndex(index);
        dto.setWorkerJobId(jobRunId + ":" + index);
        dto.setStepId(stepDef.id());
        dto.setStepName(stepDef.name() != null ? stepDef.name() : "unnamed");
        dto.setStepType(stepDef.type());

        Map<String, Object> with = stepDef.with();

        Object promptVal = with.get("prompt");
        if (promptVal != null) {
            dto.setPrompt(interpolator.interpolate(promptVal.toString(), ctx));
        }

        Object inputsVal = with.get("inputs");
        if (inputsVal instanceof Map) {
            Map<String, String> interpolatedInputs = interpolateStringMap(castStringObjectMap(inputsVal), ctx);
            dto.setInputsJson(toJson(interpolatedInputs));
        }

        Object outputSchemaVal = with.get("output_schema");
        if (outputSchemaVal != null) {
            dto.setOutputSchemaJson(toJson(outputSchemaVal));
        }

        if (with.get("timeout_minutes") instanceof Number n) {
            dto.setTimeoutMinutes(n.intValue());
        }
        if (with.get("conductor_mcp") instanceof Boolean b) {
            dto.setConductorMcp(b);
        }
        if (with.get("allowed_tools") != null) {
            dto.setAllowedTools(with.get("allowed_tools").toString());
        }
        if (with.get("max_turns") instanceof Number n) {
            dto.setMaxTurns(n.intValue());
        }

        Object envVal = stepDef.raw().get("env");
        if (envVal instanceof Map) {
            dto.setEnv(interpolateStringMap(castStringObjectMap(envVal), ctx));
        }

        return dto;
    }

    private String resolveJobImage(JobSpec jobDef, List<StepSpec> steps) {
        Object containerObj = jobDef.raw().get("container");
        if (containerObj instanceof Map<?, ?> container && container.get("image") != null) {
            return container.get("image").toString();
        }
        for (StepSpec step : steps) {
            if (step.raw().get("uses") instanceof String uses && uses.startsWith(DOCKER_USES_PREFIX)) {
                String image = uses.substring(DOCKER_USES_PREFIX.length()).trim();
                if (!image.isEmpty()) return image;
            }
        }
        for (StepSpec step : steps) {
            if ("claude-code".equals(step.type())) {
                return RunnerImage.DEFAULT;
            }
        }
        return null;
    }

    private Map<String, String> interpolateStringMap(Map<String, Object> raw, RuntimeContext ctx) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String value = entry.getValue() != null ? interpolator.interpolate(entry.getValue().toString(), ctx) : "";
            result.put(entry.getKey(), value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castStringObjectMap(Object obj) {
        return (Map<String, Object>) obj;
    }

    private Map<String, Map<String, String>> collectUpstreamOutputs(WorkflowRun run, Map<String, JobSpec> jobs, String currentJobId) {
        return upstreamOutputsResolver.collectUpstreamOutputs(run, jobs, currentJobId);
    }

    private int loadTokenTtlHours(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getRunTokenTtlHours)
                .orElse(24);
    }

    private WorkflowSpec parseYaml(String yaml) {
        try {
            return yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
