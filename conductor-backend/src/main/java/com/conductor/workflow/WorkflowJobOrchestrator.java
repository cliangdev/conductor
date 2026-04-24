package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // Self-reference injected lazily so @Transactional helpers are invoked through the Spring proxy
    // even when called from the same class. Required because executeJob() is deliberately NOT
    // @Transactional so no DB connection is held during external step I/O.
    @Lazy
    @Autowired
    private WorkflowJobOrchestrator self;

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

    /**
     * NOT @Transactional. Long-running external step I/O (HTTP polls, Docker waits, Kestra polls)
     * must not hold a DB connection — otherwise JDBC sessions accumulate in "idle in transaction"
     * when Cloud Run kills an instance mid-step, exhausting the Supavisor/Postgres pool.
     *
     * DB work is delegated to @Transactional helpers via {@link #self} so each unit of persistence
     * runs in its own short transaction.
     */
    public void executeJob(WorkflowRun run, String jobId) {
        executeJob(run.getId(), jobId);
    }

    public void executeJob(String runId, String jobId) {
        JobExecutionPlan plan = self.planJobExecution(runId, jobId);
        if (plan == null || plan.done) return;

        boolean jobFailed = runSteps(plan);

        self.finalizeJob(plan, jobFailed);
    }

    /**
     * TX boundary: load run + workflow, create/find jobRun, mark RUNNING, evaluate if-condition,
     * collect secrets + upstream outputs. All lazy associations (workflow, project) are resolved
     * here so the outer non-transactional loop can rely on primitives + pre-fetched data.
     *
     * Returns {@link JobExecutionPlan#complete()} when the if-condition causes the job to skip —
     * the skip + propagation have already been persisted inside this same transaction.
     */
    @Transactional
    public JobExecutionPlan planJobExecution(String runId, String jobId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Run {} not found, skipping job {}", runId, jobId);
            return null;
        }

        WorkflowDefinition workflow = run.getWorkflow();
        Map<String, Object> parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) parsedWorkflow.get("jobs");
        if (jobs == null || !jobs.containsKey(jobId)) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobDef = (Map<String, Object>) jobs.get(jobId);

        WorkflowJobRun jobRun = findOrCreateLatestJobRun(run, jobId);

        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        jobRun.setStartedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);

        String projectId = workflow.getProject().getId();
        int loopIteration = jobRun.getIteration() + 1;

        String ifCondition = (String) jobDef.get("if");
        if (ifCondition != null) {
            Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);
            Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
            RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs, loopIteration);
            String interpolated = interpolator.interpolate(ifCondition, ctx);
            if (!conditionEvaluator.evaluate(interpolated)) {
                jobRun.setStatus(WorkflowJobStatus.SKIPPED);
                jobRun.setCompletedAt(OffsetDateTime.now());
                jobRunRepository.save(jobRun);
                propagateSkipToDependents(run, jobId, jobs);
                return JobExecutionPlan.complete();
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) jobDef.get("steps");
        if (steps == null) steps = List.of();
        List<Map<String, Object>> executableSteps = steps.stream()
                .filter(s -> !"condition".equals(s.get("type")))
                .toList();

        Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
        Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);

        return new JobExecutionPlan(runId, jobId, jobRun.getId(), projectId, loopIteration,
                jobs, jobDef, steps, executableSteps, secrets, upstreamOutputs, false);
    }

    /**
     * Iterates executable steps with NO outer transaction. Each step's backend.execute() runs
     * external I/O unencumbered; the per-step DB write happens in its own short transaction
     * via {@link #persistStepResult}.
     */
    private boolean runSteps(JobExecutionPlan plan) {
        boolean jobFailed = false;
        for (Map<String, Object> stepDef : plan.executableSteps) {
            if (jobFailed) break;
            RuntimeContext ctx = self.buildStepContext(plan.runId, plan.jobRunId,
                    plan.secrets, plan.upstreamOutputs, plan.loopIteration);
            StepResult result = runStep(plan.runId, plan.jobRunId, stepDef, ctx, plan.projectId);
            self.persistStepResult(plan.jobRunId, stepDef, result, plan.projectId);
            if (result.getStatus() == WorkflowStepStatus.FAILED) {
                jobFailed = true;
            }
        }
        return jobFailed;
    }

    /**
     * Runs a single step's backend. NO transaction here — backend.execute() may block for
     * minutes on external I/O (HTTP timeouts, Docker polling, Kestra polling).
     */
    private StepResult runStep(String runId, String jobRunId, Map<String, Object> stepDef,
                               RuntimeContext ctx, String projectId) {
        String stepType = resolveStepType(stepDef);
        String ifCond = (String) stepDef.get("if");
        if (ifCond != null) {
            String interpolated = interpolator.interpolate(ifCond, ctx);
            if (!conditionEvaluator.evaluate(interpolated)) {
                return StepResult.skipped();
            }
        }

        WorkflowExecutionBackend backend = backends.get(stepType);
        if (backend == null) {
            return StepResult.failed("", "Unknown step type: " + stepType);
        }

        // Executors only read non-lazy primitives (IDs). Load once inside a short read-only tx.
        StepExecutionContext execCtx = self.buildStepExecutionContext(runId, jobRunId, stepDef, ctx, projectId);
        return backend.execute(execCtx);
    }

    @Transactional(readOnly = true)
    public StepExecutionContext buildStepExecutionContext(String runId, String jobRunId,
                                                          Map<String, Object> stepDef,
                                                          RuntimeContext ctx, String projectId) {
        WorkflowRun run = runRepository.findById(runId).orElseThrow();
        WorkflowJobRun jobRun = jobRunRepository.findById(jobRunId).orElseThrow();
        return new StepExecutionContext(run, jobRun, stepDef, ctx, projectId);
    }

    @Transactional(readOnly = true)
    public RuntimeContext buildStepContext(String runId, String jobRunId,
                                           Map<String, String> secrets,
                                           Map<String, Map<String, String>> upstreamOutputs,
                                           int loopIteration) {
        WorkflowRun run = runRepository.findById(runId).orElseThrow();
        WorkflowJobRun jobRun = jobRunRepository.findById(jobRunId).orElseThrow();
        return contextBuilder.build(run, jobRun, secrets, upstreamOutputs, loopIteration);
    }

    @Transactional
    public void persistStepResult(String jobRunId, Map<String, Object> stepDef,
                                   StepResult result, String projectId) {
        WorkflowJobRun jobRun = jobRunRepository.findById(jobRunId).orElseThrow();
        String stepId = (String) stepDef.get("id");
        String stepName = (String) stepDef.getOrDefault("name", "unnamed");
        String stepType = resolveStepType(stepDef);
        persistStepRun(jobRun, stepId, stepName, stepType, result, projectId);
    }

    /**
     * TX boundary: set terminal job status, propagate skips/failures, enqueue dependents or
     * handle loop/condition tail. Runs after all step I/O has completed.
     */
    @Transactional
    public void finalizeJob(JobExecutionPlan plan, boolean jobFailed) {
        WorkflowRun run = runRepository.findById(plan.runId).orElseThrow();
        WorkflowJobRun jobRun = jobRunRepository.findById(plan.jobRunId).orElseThrow();

        if (jobFailed) {
            jobRun.setStatus(WorkflowJobStatus.FAILED);
            jobRun.setCompletedAt(OffsetDateTime.now());
            jobRunRepository.save(jobRun);
            propagateFailureToDependents(run, plan.jobId, plan.jobs);
            return;
        }

        List<Map<String, Object>> steps = plan.allSteps;
        Map<String, Object> lastStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (lastStep != null && "condition".equals(lastStep.get("type"))) {
            handleConditionStep(run, jobRun, lastStep, plan.secrets, plan.upstreamOutputs,
                    plan.loopIteration, plan.jobId, plan.jobs);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> loopDef = (Map<String, Object>) plan.jobDef.get("loop");
        if (loopDef != null) {
            handleLoop(run, jobRun, plan.jobDef, loopDef, plan.jobId, plan.jobs,
                    plan.secrets, plan.upstreamOutputs, plan.loopIteration);
            return;
        }

        jobRun.setStatus(WorkflowJobStatus.SUCCESS);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);
        enqueueReadyDependents(run, plan.jobId, plan.jobs);
    }

    private WorkflowJobRun findOrCreateLatestJobRun(WorkflowRun run, String jobId) {
        List<WorkflowJobRun> existing = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(run.getId(), jobId);
        if (!existing.isEmpty()) {
            WorkflowJobRun latest = existing.get(0);
            // Only reuse if it's PENDING (newly created for a loop re-enqueue)
            if (latest.getStatus() == WorkflowJobStatus.PENDING) {
                return latest;
            }
        }
        WorkflowJobRun jr = new WorkflowJobRun();
        jr.setRun(run);
        jr.setJobId(jobId);
        jr.setStatus(WorkflowJobStatus.PENDING);
        return jobRunRepository.save(jr);
    }

    @SuppressWarnings("unchecked")
    private void handleLoop(WorkflowRun run, WorkflowJobRun jobRun,
                            Map<String, Object> jobDef,
                            Map<String, Object> loopDef,
                            String jobId, Map<String, Object> jobs,
                            Map<String, String> secrets,
                            Map<String, Map<String, String>> upstreamOutputs,
                            int loopIteration) {
        int maxIterations = ((Number) loopDef.get("max_iterations")).intValue();
        String untilExpr = (String) loopDef.get("until");
        boolean failOnExhausted = !Boolean.FALSE.equals(loopDef.get("fail_on_exhausted"));

        RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs, loopIteration);
        String interpolated = interpolator.interpolate(untilExpr, ctx);
        boolean isDone = conditionEvaluator.evaluate(interpolated);

        if (isDone) {
            jobRun.setStatus(WorkflowJobStatus.SUCCESS);
            jobRun.setCompletedAt(OffsetDateTime.now());
            jobRunRepository.save(jobRun);
            enqueueReadyDependents(run, jobId, jobs);
        } else if (jobRun.getIteration() < maxIterations - 1) {
            jobRun.setStatus(WorkflowJobStatus.SUCCESS);
            jobRun.setCompletedAt(OffsetDateTime.now());
            jobRunRepository.save(jobRun);

            WorkflowJobRun nextRun = new WorkflowJobRun();
            nextRun.setRun(run);
            nextRun.setJobId(jobId);
            nextRun.setIteration(jobRun.getIteration() + 1);
            nextRun.setStatus(WorkflowJobStatus.PENDING);
            jobRunRepository.save(nextRun);
            engine.enqueueJob(run.getId(), jobId);
        } else {
            WorkflowJobStatus exhaustedStatus = failOnExhausted
                    ? WorkflowJobStatus.LOOP_EXHAUSTED
                    : WorkflowJobStatus.SUCCESS;
            jobRun.setStatus(exhaustedStatus);
            jobRun.setCompletedAt(OffsetDateTime.now());
            jobRunRepository.save(jobRun);

            if (failOnExhausted) {
                propagateFailureToDependents(run, jobId, jobs);
            } else {
                enqueueReadyDependents(run, jobId, jobs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleConditionStep(WorkflowRun run, WorkflowJobRun jobRun,
                                     Map<String, Object> conditionStep,
                                     Map<String, String> secrets,
                                     Map<String, Map<String, String>> upstreamOutputs,
                                     int loopIteration,
                                     String jobId, Map<String, Object> jobs) {
        String expression = (String) conditionStep.get("expression");
        String thenJobId = (String) conditionStep.get("then");
        String elseJobId = (String) conditionStep.get("else");

        RuntimeContext ctx = contextBuilder.build(run, jobRun, secrets, upstreamOutputs, loopIteration);
        String interpolated = interpolator.interpolate(expression, ctx);
        boolean result = conditionEvaluator.evaluate(interpolated);

        String activeJobId = result ? thenJobId : elseJobId;
        String skippedJobId = result ? elseJobId : thenJobId;
        String branchName = result ? "then" : "else";

        if (skippedJobId != null) {
            skipJobWithReason(run, skippedJobId, "Condition routed to " + branchName + " branch");
            propagateSkipToDependents(run, skippedJobId, jobs);
        }
        if (activeJobId != null) {
            engine.enqueueJob(run.getId(), activeJobId);
        }

        jobRun.setStatus(WorkflowJobStatus.SUCCESS);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);
        engine.checkRunCompletion(run);
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
        skipJobWithReason(run, jobId, null);
    }

    private void skipJobWithReason(WorkflowRun run, String jobId, String reason) {
        WorkflowJobRun jobRun = jobRunRepository.findByRunIdAndJobId(run.getId(), jobId)
                .orElseGet(() -> {
                    WorkflowJobRun jr = new WorkflowJobRun();
                    jr.setRun(run);
                    jr.setJobId(jobId);
                    return jr;
                });
        jobRun.setStatus(WorkflowJobStatus.SKIPPED);
        jobRun.setCompletedAt(OffsetDateTime.now());
        if (reason != null) {
            jobRun.setSkipReason(reason);
        }
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
            List<WorkflowJobRun> depJobRuns = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(run.getId(), depJobId);
            if (depJobRuns.isEmpty()) continue;
            String depJobRunId = depJobRuns.get(0).getId();

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

    private String resolveStepType(Map<String, Object> stepDef) {
        Object usesVal = stepDef.get("uses");
        if (usesVal instanceof String uses && uses.startsWith("docker://")) {
            return "docker";
        }
        return (String) stepDef.getOrDefault("type", "http");
    }

    private Map<String, Object> parseYaml(String yaml) {
        try {
            return new org.yaml.snakeyaml.Yaml().load(yaml);
        } catch (Exception e) {
            log.error("Failed to parse YAML: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Snapshot of data needed to execute a job's steps outside of any DB transaction.
     * Populated by {@link #planJobExecution} inside a transaction; consumed by the
     * non-transactional step-execution loop and by {@link #finalizeJob}.
     */
    public static final class JobExecutionPlan {
        final String runId;
        final String jobId;
        final String jobRunId;
        final String projectId;
        final int loopIteration;
        final Map<String, Object> jobs;
        final Map<String, Object> jobDef;
        final List<Map<String, Object>> allSteps;
        final List<Map<String, Object>> executableSteps;
        final Map<String, String> secrets;
        final Map<String, Map<String, String>> upstreamOutputs;
        final boolean done;

        JobExecutionPlan(String runId, String jobId, String jobRunId, String projectId,
                         int loopIteration, Map<String, Object> jobs, Map<String, Object> jobDef,
                         List<Map<String, Object>> allSteps, List<Map<String, Object>> executableSteps,
                         Map<String, String> secrets, Map<String, Map<String, String>> upstreamOutputs,
                         boolean done) {
            this.runId = runId;
            this.jobId = jobId;
            this.jobRunId = jobRunId;
            this.projectId = projectId;
            this.loopIteration = loopIteration;
            this.jobs = jobs;
            this.jobDef = jobDef;
            this.allSteps = allSteps;
            this.executableSteps = executableSteps;
            this.secrets = secrets;
            this.upstreamOutputs = upstreamOutputs;
            this.done = done;
        }

        static JobExecutionPlan complete() {
            return new JobExecutionPlan(null, null, null, null, 0,
                    null, null, null, null, null, null, true);
        }
    }
}
