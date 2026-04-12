package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Component
public class WorkflowJobOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJobOrchestrator.class);

    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowExecutionEngine engine;
    private final ConditionEvaluator conditionEvaluator;
    private final WorkflowInterpolator interpolator;
    private final RuntimeContextBuilder contextBuilder;
    private final LogRedactionService logRedactionService;
    private final Map<String, WorkflowExecutionBackend> backends;
    private final ObjectMapper objectMapper;

    public WorkflowJobOrchestrator(WorkflowJobRunRepository jobRunRepository,
                                   WorkflowStepRunRepository stepRunRepository,
                                   WorkflowRunRepository runRepository,
                                   WorkflowDefinitionRepository workflowRepository,
                                   @Lazy WorkflowExecutionEngine engine,
                                   ConditionEvaluator conditionEvaluator,
                                   WorkflowInterpolator interpolator,
                                   RuntimeContextBuilder contextBuilder,
                                   LogRedactionService logRedactionService,
                                   List<WorkflowExecutionBackend> backends,
                                   ObjectMapper objectMapper) {
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.conditionEvaluator = conditionEvaluator;
        this.interpolator = interpolator;
        this.contextBuilder = contextBuilder;
        this.logRedactionService = logRedactionService;
        this.objectMapper = objectMapper;
        Map<String, WorkflowExecutionBackend> backendMap = new HashMap<>();
        for (WorkflowExecutionBackend b : backends) backendMap.put(b.getStepType(), b);
        this.backends = backendMap;
    }

    @Transactional
    public void executeJob(WorkflowRun run, String jobId) {
        WorkflowDefinition workflow = run.getWorkflow();
        Map<String, Object> parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) parsedWorkflow.get("jobs");
        if (jobs == null || !jobs.containsKey(jobId)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobDef = (Map<String, Object>) jobs.get(jobId);

        WorkflowJobRun jobRun = jobRunRepository.findByRunIdAndJobId(run.getId(), jobId)
                .orElseGet(() -> {
                    WorkflowJobRun jr = new WorkflowJobRun();
                    jr.setRun(run);
                    jr.setJobId(jobId);
                    jr.setStatus(WorkflowJobStatus.PENDING);
                    return jobRunRepository.save(jr);
                });

        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        jobRun.setStartedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);

        String projectId = workflow.getProject().getId();

        // Evaluate job-level if condition
        String ifCondition = (String) jobDef.get("if");
        if (ifCondition != null) {
            Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);
            Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
            RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs);
            String interpolated = interpolator.interpolate(ifCondition, ctx);
            if (!conditionEvaluator.evaluate(interpolated)) {
                jobRun.setStatus(WorkflowJobStatus.SKIPPED);
                jobRun.setCompletedAt(OffsetDateTime.now());
                jobRunRepository.save(jobRun);
                propagateSkipToDependents(run, jobId, jobs);
                return;
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) jobDef.get("steps");
        if (steps == null) steps = List.of();

        Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
        Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);

        boolean jobFailed = false;
        for (Map<String, Object> stepDef : steps) {
            if (jobFailed) break;
            RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs);
            StepResult result = executeStep(run, jobRun, stepDef, ctx, projectId);
            if (result.getStatus() == WorkflowStepStatus.FAILED) {
                jobFailed = true;
            }
        }

        jobRun.setStatus(jobFailed ? WorkflowJobStatus.FAILED : WorkflowJobStatus.SUCCESS);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);

        if (jobFailed) {
            propagateFailureToDependents(run, jobId, jobs);
        } else {
            enqueueReadyDependents(run, jobId, jobs);
        }
    }

    private StepResult executeStep(WorkflowRun run, WorkflowJobRun jobRun,
                                   Map<String, Object> stepDef, RuntimeContext ctx, String projectId) {
        String stepId = (String) stepDef.get("id");
        String stepName = (String) stepDef.getOrDefault("name", "unnamed");
        String stepType = (String) stepDef.getOrDefault("type", "http");

        String ifCond = (String) stepDef.get("if");
        if (ifCond != null) {
            String interpolated = interpolator.interpolate(ifCond, ctx);
            if (!conditionEvaluator.evaluate(interpolated)) {
                persistStepRun(jobRun, stepId, stepName, stepType, StepResult.skipped(), projectId);
                return StepResult.skipped();
            }
        }

        WorkflowExecutionBackend backend = backends.get(stepType);
        StepResult result;
        if (backend == null) {
            result = StepResult.failed("", "Unknown step type: " + stepType);
        } else {
            StepExecutionContext execCtx = new StepExecutionContext(run, jobRun, stepDef, ctx, projectId);
            result = backend.execute(execCtx);
        }

        persistStepRun(jobRun, stepId, stepName, stepType, result, projectId);
        return result;
    }

    private void persistStepRun(WorkflowJobRun jobRun, String stepId, String stepName,
                                String stepType, StepResult result, String projectId) {
        WorkflowStepRun stepRun = new WorkflowStepRun();
        stepRun.setJobRun(jobRun);
        stepRun.setStepId(stepId);
        stepRun.setStepName(stepName);
        stepRun.setStepType(stepType);
        stepRun.setStatus(result.getStatus());

        String redactedLog = result.getLog() != null
                ? logRedactionService.redact(projectId, result.getLog())
                : null;
        stepRun.setLog(redactedLog);
        stepRun.setErrorReason(result.getErrorReason());

        if (!result.getOutputs().isEmpty()) {
            try {
                stepRun.setOutputJson(objectMapper.writeValueAsString(result.getOutputs()));
            } catch (Exception e) {
                log.warn("Failed to serialize step outputs: {}", e.getMessage());
            }
        }
        stepRun.setStartedAt(OffsetDateTime.now());
        stepRun.setCompletedAt(OffsetDateTime.now());
        stepRunRepository.save(stepRun);
    }

    private void propagateSkipToDependents(WorkflowRun run, String skippedJobId, Map<String, Object> jobs) {
        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            List<String> needs = getNeedsList(job);
            if (needs.contains(skippedJobId)) {
                skipJob(run, entry.getKey());
                propagateSkipToDependents(run, entry.getKey(), jobs);
            }
        }
    }

    private void propagateFailureToDependents(WorkflowRun run, String failedJobId, Map<String, Object> jobs) {
        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            List<String> needs = getNeedsList(job);
            if (needs.contains(failedJobId)) {
                skipJob(run, entry.getKey());
                propagateSkipToDependents(run, entry.getKey(), jobs);
            }
        }
    }

    private void enqueueReadyDependents(WorkflowRun run, String completedJobId, Map<String, Object> jobs) {
        List<WorkflowJobRun> existingJobRuns = jobRunRepository.findByRunId(run.getId());
        Set<String> completedJobIds = new HashSet<>();
        for (WorkflowJobRun jr : existingJobRuns) {
            if (jr.getStatus() == WorkflowJobStatus.SUCCESS || jr.getStatus() == WorkflowJobStatus.SKIPPED) {
                completedJobIds.add(jr.getJobId());
            }
        }

        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            String jobId = entry.getKey();
            if (completedJobIds.contains(jobId)) continue;
            if (!(entry.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            List<String> needs = getNeedsList(job);
            if (!needs.isEmpty() && completedJobIds.containsAll(needs)) {
                engine.enqueueJob(run.getId(), jobId);
            }
        }
    }

    private void skipJob(WorkflowRun run, String jobId) {
        WorkflowJobRun jobRun = jobRunRepository.findByRunIdAndJobId(run.getId(), jobId)
                .orElseGet(() -> {
                    WorkflowJobRun jr = new WorkflowJobRun();
                    jr.setRun(run);
                    jr.setJobId(jobId);
                    return jr;
                });
        jobRun.setStatus(WorkflowJobStatus.SKIPPED);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);
    }

    private Map<String, Map<String, String>> collectUpstreamOutputs(WorkflowRun run,
                                                                     Map<String, Object> jobs,
                                                                     String currentJobId) {
        Map<String, Map<String, String>> result = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> currentJob = (Map<String, Object>) jobs.get(currentJobId);
        List<String> needs = getNeedsList(currentJob);

        for (String depJobId : needs) {
            Optional<WorkflowJobRun> depJobRunOpt = jobRunRepository.findByRunIdAndJobId(run.getId(), depJobId);
            if (depJobRunOpt.isEmpty()) continue;
            String depJobRunId = depJobRunOpt.get().getId();

            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(depJobRunId);
            Map<String, String> jobOutputs = new HashMap<>();
            for (WorkflowStepRun step : steps) {
                if (step.getOutputJson() != null) {
                    try {
                        Map<String, String> outputs = objectMapper.readValue(
                                step.getOutputJson(), new TypeReference<>() {});
                        jobOutputs.putAll(outputs);
                    } catch (Exception ignored) {}
                }
            }
            result.put(depJobId, jobOutputs);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> getNeedsList(Map<String, Object> job) {
        Object needs = job.get("needs");
        if (needs == null) return List.of();
        if (needs instanceof List) return (List<String>) needs;
        if (needs instanceof String) return List.of((String) needs);
        return List.of();
    }

    private Map<String, Object> parseYaml(String yaml) {
        try {
            return new org.yaml.snakeyaml.Yaml().load(yaml);
        } catch (Exception e) {
            log.error("Failed to parse YAML: {}", e.getMessage());
            return null;
        }
    }
}
