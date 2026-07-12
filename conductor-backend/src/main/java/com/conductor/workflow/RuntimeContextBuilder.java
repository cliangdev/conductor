package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.WorkflowArtifactService;
import com.conductor.service.WorkflowSecretsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a RuntimeContext for a step execution.
 * Secrets loaded once per job run (not per step) to minimize DB queries.
 */
@Component
public class RuntimeContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RuntimeContextBuilder.class);

    private final WorkflowSecretsService secretsService;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowArtifactService artifactService;

    public RuntimeContextBuilder(WorkflowSecretsService secretsService,
                                  WorkflowStepRunRepository stepRunRepository,
                                  WorkflowJobRunRepository jobRunRepository,
                                  ObjectMapper objectMapper,
                                  WorkflowArtifactService artifactService) {
        this.secretsService = secretsService;
        this.stepRunRepository = stepRunRepository;
        this.jobRunRepository = jobRunRepository;
        this.objectMapper = objectMapper;
        this.artifactService = artifactService;
    }

    /**
     * Build context for a step, outside a loop job (iteration 0).
     *
     * @param needs the current job's {@code needs} — used to populate {@code needs.<job>.result}
     */
    public RuntimeContext build(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, String> secrets,
                                Map<String, Map<String, String>> upstreamJobOutputs,
                                List<String> needs) {
        return build(run, jobRun, secrets, upstreamJobOutputs, needs, 0);
    }

    /**
     * Build context for a step with loop iteration (1-based).
     *
     * @param needs the current job's {@code needs} — used to populate {@code needs.<job>.result}
     */
    public RuntimeContext build(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, String> secrets,
                                Map<String, Map<String, String>> upstreamJobOutputs,
                                List<String> needs,
                                int loopIteration) {
        Map<String, Object> eventPayload = parseEventPayload(run.getEventPayload());

        Map<String, Map<String, String>> stepOutputs = new HashMap<>();
        Map<String, String> stepResults = new HashMap<>();
        List<WorkflowStepRun> priorSteps = stepRunRepository.findByJobRunIdOrderByStartedAtAscIdAsc(jobRun.getId());
        for (WorkflowStepRun step : priorSteps) {
            if (step.getStepId() == null) continue;
            if (step.getOutputJson() != null) {
                stepOutputs.put(step.getStepId(), parseOutputJson(step.getOutputJson()));
            }
            String result = stepResult(step.getStatus());
            if (result != null) {
                stepResults.put(step.getStepId(), result);
            }
        }

        Map<String, String> jobResults = new HashMap<>();
        if (needs != null) {
            for (String needJobId : needs) {
                List<WorkflowJobRun> depRuns =
                        jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(run.getId(), needJobId);
                if (depRuns.isEmpty()) continue;
                String result = jobResult(depRuns.get(0).getStatus());
                if (result != null) {
                    jobResults.put(needJobId, result);
                }
            }
        }

        Map<String, String> inputs = parseInputs(eventPayload);

        Map<String, Map<String, String>> jobArtifacts = new HashMap<>();
        if (needs != null) {
            for (String needJobId : needs) {
                Map<String, String> artifacts = artifactService.resolveUploadedArtifacts(run.getId(), needJobId);
                if (!artifacts.isEmpty()) {
                    jobArtifacts.put(needJobId, artifacts);
                }
            }
        }

        return new RuntimeContext(eventPayload, secrets, stepOutputs, upstreamJobOutputs, loopIteration,
                jobResults, stepResults, inputs, jobArtifacts);
    }

    /** Load secrets once for the project — call this once per job, not per step */
    public Map<String, String> loadSecrets(String projectId) {
        return secretsService.resolveSecrets(projectId);
    }

    /** Maps a terminal {@link WorkflowJobStatus} to the {@code needs.<job>.result} vocabulary. */
    private static String jobResult(WorkflowJobStatus status) {
        return switch (status) {
            case SUCCESS -> "success";
            case FAILED, LOOP_EXHAUSTED -> "failure";
            case SKIPPED -> "skipped";
            default -> null; // non-terminal — shouldn't be reachable for a ready need
        };
    }

    /** Maps a terminal {@link WorkflowStepStatus} to the {@code steps.<id>.result} vocabulary. */
    private static String stepResult(WorkflowStepStatus status) {
        return switch (status) {
            case SUCCESS -> "success";
            case FAILED -> "failure";
            case SKIPPED -> "skipped";
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseInputs(Map<String, Object> eventPayload) {
        Object inputsObj = eventPayload.get("inputs");
        if (!(inputsObj instanceof Map)) return Collections.emptyMap();
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) inputsObj).entrySet()) {
            result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }

    private Map<String, Object> parseEventPayload(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse event payload JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, String> parseOutputJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse output JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
