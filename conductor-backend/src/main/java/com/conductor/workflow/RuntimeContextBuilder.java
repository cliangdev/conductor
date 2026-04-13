package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
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

    public RuntimeContextBuilder(WorkflowSecretsService secretsService,
                                  WorkflowStepRunRepository stepRunRepository,
                                  WorkflowJobRunRepository jobRunRepository,
                                  ObjectMapper objectMapper) {
        this.secretsService = secretsService;
        this.stepRunRepository = stepRunRepository;
        this.jobRunRepository = jobRunRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Build context for a step. Secrets are provided externally (loaded once per job).
     */
    public RuntimeContext build(WorkflowRun run, WorkflowJobRun jobRun,
                                Map<String, String> secrets,
                                Map<String, Map<String, String>> upstreamJobOutputs) {
        Map<String, Object> eventPayload = parseEventPayload(run.getEventPayload());

        Map<String, Map<String, String>> stepOutputs = new HashMap<>();
        List<WorkflowStepRun> priorSteps = stepRunRepository.findByJobRunId(jobRun.getId());
        for (WorkflowStepRun step : priorSteps) {
            if (step.getStepId() != null && step.getOutputJson() != null) {
                Map<String, String> outputs = parseOutputJson(step.getOutputJson());
                stepOutputs.put(step.getStepId(), outputs);
            }
        }

        return new RuntimeContext(eventPayload, secrets, stepOutputs, upstreamJobOutputs);
    }

    /** Load secrets once for the project — call this once per job, not per step */
    public Map<String, String> loadSecrets(String projectId) {
        return secretsService.resolveSecrets(projectId);
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
