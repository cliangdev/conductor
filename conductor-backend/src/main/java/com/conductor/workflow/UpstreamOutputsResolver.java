package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@code needs.<job>.outputs} map a job's steps interpolate against: for each job
 * listed in {@code needs}, the merged step outputs of that dependency's latest run. Extracted from
 * {@link WorkflowJobOrchestrator} and {@link JobDispatchPayloadService}, which both computed this
 * identically (conductor-hosted planning vs. self-hosted dispatch-payload building need the exact
 * same lookup) before this shared home existed.
 */
@Component
public class UpstreamOutputsResolver {

    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ObjectMapper objectMapper;

    public UpstreamOutputsResolver(WorkflowJobRunRepository jobRunRepository,
                                    WorkflowStepRunRepository stepRunRepository,
                                    ObjectMapper objectMapper) {
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Map<String, String>> collectUpstreamOutputs(WorkflowRun run,
                                                                     Map<String, Object> jobs,
                                                                     String currentJobId) {
        Map<String, Map<String, String>> result = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> currentJob = (Map<String, Object>) jobs.get(currentJobId);
        List<String> needs = getNeedsList(currentJob);

        for (String depJobId : needs) {
            List<WorkflowJobRun> depJobRuns = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(run.getId(), depJobId);
            if (depJobRuns.isEmpty()) continue;
            String depJobRunId = depJobRuns.get(0).getId();

            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(depJobRunId);
            Map<String, String> jobOutputs = new HashMap<>();
            for (WorkflowStepRun step : steps) {
                if (step.getOutputJson() != null) {
                    try {
                        Map<String, String> outputs = objectMapper.readValue(
                                step.getOutputJson(), new TypeReference<Map<String, String>>() {});
                        jobOutputs.putAll(outputs);
                    } catch (Exception ignored) {
                    }
                }
            }
            result.put(depJobId, jobOutputs);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<String> getNeedsList(Map<String, Object> job) {
        Object needs = job.get("needs");
        if (needs == null) return List.of();
        if (needs instanceof List) return (List<String>) needs;
        if (needs instanceof String) return List.of((String) needs);
        return List.of();
    }
}
