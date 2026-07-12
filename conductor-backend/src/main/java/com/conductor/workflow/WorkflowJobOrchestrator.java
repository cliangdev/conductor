package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.conductor.service.LogRedactionService;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.LoopSpec;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
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
    private final SelfHostedJobDispatcher selfHostedJobDispatcher;
    private final UpstreamOutputsResolver upstreamOutputsResolver;
    private final WorkflowYamlParser yamlParser;

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
                                   ObjectMapper objectMapper,
                                   SelfHostedJobDispatcher selfHostedJobDispatcher,
                                   UpstreamOutputsResolver upstreamOutputsResolver,
                                   WorkflowYamlParser yamlParser) {
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
        this.selfHostedJobDispatcher = selfHostedJobDispatcher;
        this.upstreamOutputsResolver = upstreamOutputsResolver;
        this.yamlParser = yamlParser;
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
        WorkflowSpec parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return null;

        Map<String, JobSpec> jobs = parsedWorkflow.jobs();
        if (!jobs.containsKey(jobId)) return null;

        JobSpec jobDef = jobs.get(jobId);

        boolean selfHosted = "self-hosted".equals(jobDef.runsOn());
        if (selfHosted) {
            // Lock the run row so a duplicate readiness trigger for this job (e.g. two dependents in
            // a diamond `needs` both becoming ready at once) can't race past the check below and
            // dispatch the same job to the daemon twice.
            runRepository.findByIdForUpdate(runId);
            List<WorkflowJobRun> latestForJob = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(runId, jobId);
            if (!latestForJob.isEmpty() && latestForJob.get(0).getStatus() == WorkflowJobStatus.AWAITING_PICKUP) {
                log.info("planJobExecution: job {} (run {}) already AWAITING_PICKUP, skipping duplicate dispatch", jobId, runId);
                return JobExecutionPlan.complete();
            }
        }

        WorkflowJobRun jobRun = findOrCreateLatestJobRun(run, jobId);

        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        jobRun.setStartedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);

        String projectId = workflow.getProject().getId();
        int loopIteration = jobRun.getIteration() + 1;

        String ifCondition = jobDef.ifCondition();
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

        if (selfHosted) {
            selfHostedJobDispatcher.dispatch(run, jobId, jobRun, jobDef);
            jobRun.setStatus(WorkflowJobStatus.AWAITING_PICKUP);
            jobRunRepository.save(jobRun);
            return JobExecutionPlan.complete();
        }

        List<StepSpec> executableSteps = jobDef.executableSteps();

        Map<String, String> secrets = contextBuilder.loadSecrets(projectId);
        Map<String, Map<String, String>> upstreamOutputs = collectUpstreamOutputs(run, jobs, jobId);

        return new JobExecutionPlan(runId, jobId, jobRun.getId(), projectId, loopIteration,
                jobs, jobDef, jobDef.steps(), executableSteps, secrets, upstreamOutputs, false);
    }

    /**
     * Iterates executable steps with NO outer transaction. Each step's backend.execute() runs
     * external I/O unencumbered; the per-step DB write happens in its own short transaction
     * via {@link #persistStepResult}.
     */
    private boolean runSteps(JobExecutionPlan plan) {
        boolean jobFailed = false;
        String runsOn = plan.jobDef.runsOn();
        for (StepSpec stepDef : plan.executableSteps) {
            if (jobFailed) break;
            RuntimeContext ctx = self.buildStepContext(plan.runId, plan.jobRunId,
                    plan.secrets, plan.upstreamOutputs, plan.loopIteration);
            StepResult result = runStep(plan.runId, plan.jobRunId, stepDef, ctx, plan.projectId, runsOn);
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
    private StepResult runStep(String runId, String jobRunId, StepSpec stepDef,
                               RuntimeContext ctx, String projectId, String runsOn) {
        String stepType = stepDef.type();
        String ifCond = stepDef.ifCondition();
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
        StepExecutionContext execCtx = self.buildStepExecutionContext(runId, jobRunId, stepDef.effectiveConfig(), ctx, projectId, runsOn);
        return backend.execute(execCtx);
    }

    @Transactional(readOnly = true)
    public StepExecutionContext buildStepExecutionContext(String runId, String jobRunId,
                                                          Map<String, Object> stepDef,
                                                          RuntimeContext ctx, String projectId, String runsOn) {
        WorkflowRun run = runRepository.findById(runId).orElseThrow();
        WorkflowJobRun jobRun = jobRunRepository.findById(jobRunId).orElseThrow();
        return new StepExecutionContext(run, jobRun, stepDef, ctx, projectId, runsOn);
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
    public void persistStepResult(String jobRunId, StepSpec stepDef,
                                   StepResult result, String projectId) {
        WorkflowJobRun jobRun = jobRunRepository.findById(jobRunId).orElseThrow();
        String stepId = stepDef.id();
        String stepName = stepDef.name() != null ? stepDef.name() : "unnamed";
        String stepType = stepDef.type();
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

        List<StepSpec> steps = plan.allSteps;
        StepSpec lastStep = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (lastStep != null && "condition".equals(lastStep.type())) {
            handleConditionStep(run, jobRun, lastStep, plan.secrets, plan.upstreamOutputs,
                    plan.loopIteration, plan.jobId, plan.jobs);
            return;
        }

        LoopSpec loopDef = plan.jobDef.loop();
        if (loopDef != null) {
            handleLoop(run, jobRun, loopDef, plan.jobId, plan.jobs,
                    plan.secrets, plan.upstreamOutputs, plan.loopIteration);
            return;
        }

        jobRun.setStatus(WorkflowJobStatus.SUCCESS);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);
        enqueueReadyDependents(run, plan.jobId, plan.jobs);
    }

    /**
     * Called by the daemon-facing REST endpoints (dispatch-payload/complete) and by the pickup-timeout
     * sweep to close out a self-hosted job the backend never ran itself. Marks the latest jobRun (and
     * any of its pre-created, still-non-terminal step runs) terminal, then propagates to dependents the
     * same way {@link #finalizeJob} does. Idempotent — a no-op if the job is already terminal.
     */
    @Transactional
    public void completeRemoteJob(String runId, String jobId, WorkflowJobStatus terminalStatus, String errorReason) {
        // Lock the run row so concurrent completion signals (daemon complete, legacy PATCH shim,
        // pickup-timeout sweep) serialize here instead of racing past the terminal-status guard below.
        WorkflowRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));
        List<WorkflowJobRun> existing = jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(runId, jobId);
        if (existing.isEmpty()) {
            log.warn("completeRemoteJob: no jobRun found for run {} job {}", runId, jobId);
            return;
        }
        WorkflowJobRun jobRun = existing.get(0);
        if (isTerminalJobStatus(jobRun.getStatus())) {
            log.info("completeRemoteJob: jobRun {} already terminal ({}), ignoring", jobRun.getId(), jobRun.getStatus());
            return;
        }

        WorkflowStepStatus stepStatus = terminalStatus == WorkflowJobStatus.SUCCESS
                ? WorkflowStepStatus.SUCCESS : WorkflowStepStatus.FAILED;
        for (WorkflowStepRun stepRun : stepRunRepository.findByJobRunId(jobRun.getId())) {
            if (stepRun.getStatus() == WorkflowStepStatus.PENDING || stepRun.getStatus() == WorkflowStepStatus.RUNNING) {
                stepRun.setStatus(stepStatus);
                stepRun.setCompletedAt(OffsetDateTime.now());
                if (stepStatus == WorkflowStepStatus.FAILED && errorReason != null) {
                    stepRun.setErrorReason(errorReason);
                }
                stepRunRepository.save(stepRun);
            }
        }

        jobRun.setStatus(terminalStatus);
        jobRun.setCompletedAt(OffsetDateTime.now());
        jobRunRepository.save(jobRun);

        WorkflowSpec parsedWorkflow = parseYaml(run.getWorkflow().getYaml());
        Map<String, JobSpec> jobs = parsedWorkflow != null ? parsedWorkflow.jobs() : null;
        if (jobs != null) {
            if (terminalStatus == WorkflowJobStatus.FAILED) {
                propagateFailureToDependents(run, jobId, jobs);
            } else {
                enqueueReadyDependents(run, jobId, jobs);
            }
        } else {
            log.warn("completeRemoteJob: could not parse workflow YAML for run {} — dependents of job {} "
                    + "not propagated; run will rely on the cleanup sweep", runId, jobId);
        }
        engine.checkRunCompletion(run);
    }

    private boolean isTerminalJobStatus(WorkflowJobStatus status) {
        return status == WorkflowJobStatus.SUCCESS || status == WorkflowJobStatus.FAILED
                || status == WorkflowJobStatus.SKIPPED || status == WorkflowJobStatus.LOOP_EXHAUSTED;
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

    private void handleLoop(WorkflowRun run, WorkflowJobRun jobRun,
                            LoopSpec loopDef,
                            String jobId, Map<String, JobSpec> jobs,
                            Map<String, String> secrets,
                            Map<String, Map<String, String>> upstreamOutputs,
                            int loopIteration) {
        int maxIterations = loopDef.maxIterations();
        String untilExpr = loopDef.until();
        boolean failOnExhausted = loopDef.failOnExhausted();

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

    private void handleConditionStep(WorkflowRun run, WorkflowJobRun jobRun,
                                     StepSpec conditionStep,
                                     Map<String, String> secrets,
                                     Map<String, Map<String, String>> upstreamOutputs,
                                     int loopIteration,
                                     String jobId, Map<String, JobSpec> jobs) {
        Object expressionVal = conditionStep.raw().get("expression");
        String expression = expressionVal != null ? expressionVal.toString() : null;
        Object thenVal = conditionStep.raw().get("then");
        Object elseVal = conditionStep.raw().get("else");
        String thenJobId = thenVal != null ? thenVal.toString() : null;
        String elseJobId = elseVal != null ? elseVal.toString() : null;

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

    /**
     * Updates the pre-created row in place when {@code result} carries a workerJobId matching one
     * (e.g. Cloud Run's executor, Phase 5); falls back to today's insert when no workerJobId is set
     * (all current step executors) or no matching row is found.
     *
     * <p><b>Log clobber rule:</b> {@link ClaudeCodeStepExecutor} and {@link WorkflowRunLogBroker}
     * incrementally append launcher lines and streamed container lines to the row's {@code log}
     * column <i>while the step runs</i> — that content is strictly richer than (and a superset of)
     * {@code result.getLog()}, which for that executor is just the same launcher lines built up in
     * an in-memory buffer for other {@link StepResult#getLog()} consumers. So: if the row already
     * carries non-empty log content, it is NOT overwritten by {@code result.getLog()} here — only
     * rows with no existing content (all other step types, which don't stream) get their log set
     * from the result, preserving today's behavior for them.
     */
    private void persistStepRun(WorkflowJobRun jobRun, String stepId, String stepName,
                                String stepType, StepResult result, String projectId) {
        String workerJobId = result.getWorkerJobId();
        WorkflowStepRun stepRun = workerJobId != null
                ? stepRunRepository.findByJobRunIdAndWorkerJobId(jobRun.getId(), workerJobId).orElse(null)
                : null;
        if (stepRun == null) {
            stepRun = new WorkflowStepRun();
            stepRun.setJobRun(jobRun);
            stepRun.setWorkerJobId(workerJobId);
        }
        stepRun.setStepId(stepId);
        stepRun.setStepName(stepName);
        stepRun.setStepType(stepType);
        stepRun.setStatus(result.getStatus());

        if (stepRun.getLog() == null || stepRun.getLog().isEmpty()) {
            String redactedLog = result.getLog() != null
                    ? logRedactionService.redact(projectId, result.getLog())
                    : null;
            stepRun.setLog(redactedLog);
        }
        stepRun.setErrorReason(result.getErrorReason());

        if (!result.getOutputs().isEmpty()) {
            try {
                stepRun.setOutputJson(objectMapper.writeValueAsString(result.getOutputs()));
            } catch (Exception e) {
                log.warn("Failed to serialize step outputs: {}", e.getMessage());
            }
        }
        if (stepRun.getStartedAt() == null) {
            stepRun.setStartedAt(OffsetDateTime.now());
        }
        stepRun.setCompletedAt(OffsetDateTime.now());
        stepRunRepository.save(stepRun);
    }

    private void propagateSkipToDependents(WorkflowRun run, String skippedJobId, Map<String, JobSpec> jobs) {
        for (JobSpec job : jobs.values()) {
            if (job.needs().contains(skippedJobId)) {
                skipJob(run, job.id());
                propagateSkipToDependents(run, job.id(), jobs);
            }
        }
    }

    private void propagateFailureToDependents(WorkflowRun run, String failedJobId, Map<String, JobSpec> jobs) {
        for (JobSpec job : jobs.values()) {
            if (job.needs().contains(failedJobId)) {
                skipJob(run, job.id());
                propagateSkipToDependents(run, job.id(), jobs);
            }
        }
    }

    private void enqueueReadyDependents(WorkflowRun run, String completedJobId, Map<String, JobSpec> jobs) {
        List<WorkflowJobRun> existingJobRuns = jobRunRepository.findByRunId(run.getId());
        Set<String> completedJobIds = new HashSet<>();
        for (WorkflowJobRun jr : existingJobRuns) {
            if (jr.getStatus() == WorkflowJobStatus.SUCCESS || jr.getStatus() == WorkflowJobStatus.SKIPPED) {
                completedJobIds.add(jr.getJobId());
            }
        }

        for (JobSpec job : jobs.values()) {
            if (completedJobIds.contains(job.id())) continue;
            List<String> needs = job.needs();
            if (!needs.isEmpty() && completedJobIds.containsAll(needs)) {
                engine.enqueueJob(run.getId(), job.id());
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
                                                                     Map<String, JobSpec> jobs,
                                                                     String currentJobId) {
        return upstreamOutputsResolver.collectUpstreamOutputs(run, jobs, currentJobId);
    }

    /** Parses a workflow's stored YAML, degrading to null (like the old SnakeYAML try/catch) on malformed YAML. */
    private WorkflowSpec parseYaml(String yaml) {
        try {
            return yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
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
        final Map<String, JobSpec> jobs;
        final JobSpec jobDef;
        final List<StepSpec> allSteps;
        final List<StepSpec> executableSteps;
        final Map<String, String> secrets;
        final Map<String, Map<String, String>> upstreamOutputs;
        final boolean done;

        JobExecutionPlan(String runId, String jobId, String jobRunId, String projectId,
                         int loopIteration, Map<String, JobSpec> jobs, JobSpec jobDef,
                         List<StepSpec> allSteps, List<StepSpec> executableSteps,
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
