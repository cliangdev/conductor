package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.model.ConductorEventTrigger;
import com.conductor.workflow.model.GitHubPullRequestTrigger;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowExecutionEngine executionEngine;
    private final WorkflowScheduleRepository scheduleRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowYamlParser yamlParser;
    private final WorkflowFailureCircuitBreaker circuitBreaker;

    public WorkflowTriggerService(WorkflowDefinitionRepository workflowRepository,
                                   WorkflowRunRepository workflowRunRepository,
                                   @Lazy WorkflowExecutionEngine executionEngine,
                                   WorkflowScheduleRepository scheduleRepository,
                                   ObjectMapper objectMapper,
                                   WorkflowYamlParser yamlParser,
                                   WorkflowFailureCircuitBreaker circuitBreaker) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.executionEngine = executionEngine;
        this.scheduleRepository = scheduleRepository;
        this.objectMapper = objectMapper;
        this.yamlParser = yamlParser;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Called by {@code WorkflowAutomationSignalSubscriber} when a {@link
     * SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} signal fires. Finds all enabled workflows in the
     * project with matching trigger and creates WorkflowRun rows.
     *
     * <p>The type guard is defense-in-depth: {@code WorkflowAutomationSignalSubscriber.interestedIn}
     * already filters to this type (and {@link SignalTypes#GITHUB_PULL_REQUEST}) before {@code onSignal}
     * ever calls this method, but the guard stays so a direct caller (as several unit tests are) still
     * gets the same no-op contract.
     */
    @Transactional
    public void onConductorEvent(Signal signal) {
        if (!SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signal.type())) return;

        String projectId = signal.projectId();
        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);

        for (WorkflowDefinition workflow : workflows) {
            if (!workflow.isEnabled()) continue;
            // findByProjectId returns every Workflow in the project regardless of kind — a LIFECYCLE
            // (statechart) workflow has no yaml (it uses `definition` instead), so skip those before
            // ever attempting to parse rather than relying on WorkflowYamlException for an expected,
            // common case (every project has at least the seeded ENGINEERING lifecycle workflow).
            if (workflow.getYaml() == null) continue;
            WorkflowSpec spec = parseYaml(workflow.getYaml());
            if (spec == null) continue;
            ConductorEventTrigger trigger = spec.triggers().events().stream().findFirst().orElse(null);
            if (trigger == null) continue;
            if (!passesStatusFilter(trigger, signal)) continue;

            createRun(workflow, "conductor.work_item.status_changed", buildEventPayload(signal));
        }
    }

    /**
     * Called by {@code WorkflowAutomationSignalSubscriber} when a {@link
     * SignalTypes#GITHUB_PULL_REQUEST} signal fires. Finds all enabled workflows in the project with a
     * matching {@code github.pull_request} trigger and creates WorkflowRun rows.
     *
     * <p>See {@link #onConductorEvent(Signal)} for why the type guard stays as defense-in-depth.
     */
    @Transactional
    public void onGitHubPullRequest(Signal signal) {
        if (!SignalTypes.GITHUB_PULL_REQUEST.equals(signal.type())) return;

        String projectId = signal.projectId();
        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);

        for (WorkflowDefinition workflow : workflows) {
            if (!workflow.isEnabled()) continue;
            if (workflow.getYaml() == null) continue;
            WorkflowSpec spec = parseYaml(workflow.getYaml());
            if (spec == null) continue;
            GitHubPullRequestTrigger trigger = spec.triggers().pullRequestEvents().stream().findFirst().orElse(null);
            if (trigger == null) continue;
            if (!passesPrFilters(trigger, signal)) continue;

            createRun(workflow, "github.pull_request", buildPullRequestEventPayload(signal));
        }
    }

    /**
     * Creates a run for a webhook trigger. Called from WebhookTriggerController.
     */
    @Transactional
    public WorkflowRun triggerWebhook(WorkflowDefinition workflow, String eventPayloadJson) {
        return createRun(workflow, "webhook", eventPayloadJson);
    }

    /**
     * Creates a run for manual dispatch with no inputs (e.g. a plain UI dispatch button).
     */
    @Transactional
    public WorkflowRun triggerManual(WorkflowDefinition workflow, String triggeredByUserId) {
        return triggerManual(workflow, triggeredByUserId, null);
    }

    /**
     * Creates a run for manual dispatch. Called from WorkflowDispatchController.
     *
     * @param inputs dispatch-time input values (exposed to steps as {@code ${{ inputs.KEY }}}), or
     *               null/empty to omit the key entirely — same shape as no inputs at all.
     * @throws ConflictException if the workflow declares {@code concurrency: single} and already has
     *         an active run. Scoped to manual dispatch only — deliberately NOT applied to {@link
     *         #fireTrigger}/{@link #triggerWebhook}: {@code WorkflowScheduler} already gates its own
     *         cron path before calling {@code fireTrigger}, and the knowledge-librarian workflow (also
     *         declared {@code concurrency: single}) intentionally dispatches in parallel across domain
     *         lanes via {@code LibrarianDispatchService} -- gating it here would break that.
     */
    @Transactional
    public WorkflowRun triggerManual(WorkflowDefinition workflow, String triggeredByUserId, Map<String, String> inputs) {
        if (hasActiveRunBlockingConcurrencySingle(workflow)) {
            throw new ConflictException("'" + workflow.getName()
                    + "' already has an active run — wait for it to finish before dispatching again.");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "workflow_dispatch");
        payload.put("triggeredBy", triggeredByUserId);
        payload.put("triggeredAt", java.time.OffsetDateTime.now().toString());
        if (inputs != null && !inputs.isEmpty()) {
            payload.put("inputs", inputs);
        }
        String payloadJson = toJson(payload);
        return createRun(workflow, "workflow_dispatch", payloadJson);
    }

    /**
     * Creates a run for a schedule trigger. Called from WorkflowScheduler.
     */
    @Transactional
    public WorkflowRun fireTrigger(String workflowId, String triggerType, String payloadJson) {
        WorkflowDefinition workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Workflow not found: " + workflowId));
        return createRun(workflow, triggerType, payloadJson);
    }

    /**
     * Upserts the schedule row when a workflow YAML contains a schedule trigger.
     * Deletes the schedule row if the workflow no longer has a schedule trigger.
     * Should be called after workflow create or update.
     */
    @Transactional
    public void upsertSchedule(WorkflowDefinition workflow) {
        String cronExpression = extractScheduleCron(workflow.getYaml());
        List<WorkflowSchedule> existing = scheduleRepository.findByWorkflowId(workflow.getId());

        if (cronExpression == null) {
            if (!existing.isEmpty()) {
                scheduleRepository.deleteAll(existing);
                log.info("Deleted schedule for workflow {} (no schedule trigger)", workflow.getId());
            }
            return;
        }

        WorkflowSchedule schedule = existing.isEmpty() ? new WorkflowSchedule() : existing.get(0);
        schedule.setWorkflow(workflow);
        schedule.setCronExpression(cronExpression);
        schedule.setEnabled(true);
        if (schedule.getNextRunAt() == null || !schedule.getCronExpression().equals(cronExpression)) {
            schedule.setNextRunAt(computeNextRun(cronExpression, ZonedDateTime.now(ZoneOffset.UTC)));
        }
        scheduleRepository.save(schedule);
        log.info("Upserted schedule for workflow {} with cron '{}'", workflow.getId(), cronExpression);
    }

    public java.time.OffsetDateTime computeNextRun(String cronExpression, ZonedDateTime from) {
        try {
            CronExpression expr = CronExpression.parse(cronExpression);
            ZonedDateTime next = expr.next(from);
            return next != null ? next.toOffsetDateTime() : null;
        } catch (Exception e) {
            log.warn("Failed to compute next run for cron '{}': {}", cronExpression, e.getMessage());
            return null;
        }
    }

    /** True if {@code workflow} declares {@code concurrency: single} and already has a non-terminal run. */
    private boolean hasActiveRunBlockingConcurrencySingle(WorkflowDefinition workflow) {
        WorkflowSpec spec = parseYaml(workflow.getYaml());
        if (spec == null || !"single".equals(spec.concurrency())) return false;
        return !workflowRunRepository.findByWorkflowIdAndStatusIn(workflow.getId(),
                WorkflowRunStatus.ACTIVE_RUN_STATUSES).isEmpty();
    }

    private String extractScheduleCron(String yaml) {
        WorkflowSpec spec = parseYaml(yaml);
        if (spec == null || spec.triggers().schedule() == null) return null;
        return spec.triggers().schedule().cron();
    }

    /** Parses a workflow's stored YAML, degrading to null on malformed YAML (same contract the old
     *  SnakeYAML try/catch calls had). */
    private WorkflowSpec parseYaml(String yaml) {
        try {
            return yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
            log.warn("Failed to parse workflow YAML: {}", e.getMessage());
            return null;
        }
    }

    private WorkflowRun createRun(WorkflowDefinition workflow, String triggerType, String eventPayloadJson) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setTriggerType(triggerType);
        run.setEventPayload(eventPayloadJson);
        run.setStatus(WorkflowRunStatus.PENDING);
        WorkflowRun saved = workflowRunRepository.save(run);
        log.info("Created WorkflowRun {} for workflow {} (trigger: {})", saved.getId(), workflow.getId(), triggerType);

        // All jobs flow through the queue now — self-hosted jobs dispatch per-job at readiness time
        // (WorkflowJobOrchestrator.planJobExecution -> SelfHostedJobDispatcher), not the whole run upfront.
        int enqueuedCount = enqueueInitialJobs(workflow, saved);
        if (enqueuedCount == 0) {
            // Nothing was queued (unparsable YAML, or a job graph with no eligible root job) — without
            // this, the run would sit in PENDING with jobs:[] forever: nothing else ever revisits a
            // PENDING run to retry or fail it (only the punitive 24h cleanupStuckRuns sweep would,
            // eventually). Fail loudly and immediately instead, and let the circuit breaker count it
            // like any other FAILED run so a permanently-broken workflow still auto-pauses.
            log.warn("Run {} for workflow {} enqueued zero jobs — marking FAILED instead of leaving it PENDING",
                    saved.getId(), workflow.getId());
            saved.setStatus(WorkflowRunStatus.FAILED);
            saved.setCompletedAt(java.time.OffsetDateTime.now());
            saved = workflowRunRepository.save(saved);
            circuitBreaker.recordOutcome(saved);
        }

        return saved;
    }

    /** @return the number of jobs actually enqueued (0 means the run has nothing to run and would
     *          otherwise be orphaned in PENDING forever — see the caller's fail-fast check). */
    private int enqueueInitialJobs(WorkflowDefinition workflow, WorkflowRun run) {
        WorkflowSpec spec = parseYaml(workflow.getYaml());
        if (spec == null) {
            log.warn("Failed to enqueue initial jobs for run {}: could not parse workflow YAML", run.getId());
            return 0;
        }
        Map<String, JobSpec> jobs = spec.jobs();

        // Collect condition step targets — these jobs should NOT be enqueued upfront;
        // they are enqueued at runtime when the condition step evaluates.
        java.util.Set<String> conditionTargets = collectConditionTargets(jobs);

        int enqueuedCount = 0;
        for (JobSpec job : jobs.values()) {
            if (conditionTargets.contains(job.id())) continue;
            if (job.needs().isEmpty()) {
                executionEngine.enqueueJob(run.getId(), job.id());
                enqueuedCount++;
            }
        }
        return enqueuedCount;
    }

    /**
     * Returns the set of job IDs that are targets of a condition step (then/else).
     * These jobs must not be auto-enqueued at workflow start — they are triggered
     * only when the condition step evaluates and routes to them.
     */
    private java.util.Set<String> collectConditionTargets(Map<String, JobSpec> jobs) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (JobSpec job : jobs.values()) {
            for (StepSpec step : job.steps()) {
                if (!"condition".equals(step.type())) continue;
                Object then = step.raw().get("then");
                Object else_ = step.raw().get("else");
                if (then instanceof String s) targets.add(s);
                if (else_ instanceof String s) targets.add(s);
            }
        }
        return targets;
    }

    /** Passes when no status filter is declared, or the signal's target status matches any declared entry. */
    private boolean passesStatusFilter(ConductorEventTrigger trigger, Signal signal) {
        List<String> statusFilter = trigger.statusFilter();
        if (statusFilter.isEmpty()) return true;
        String toStatus = signal.flatAttributes().get("toStatus");
        return statusFilter.stream().anyMatch(s -> s.equalsIgnoreCase(toStatus));
    }

    /**
     * Keeps {@code new HashMap<>(signal.flatAttributes())} rather than {@code signal.payload()} directly:
     * this is persisted verbatim to {@code workflow_runs.event_payload}, which customer YAML reads via
     * {@code ${{ event.workItemId }}}-style expressions, so it must stay the same flat, stringly-typed
     * shape the metadata map always was -- not whatever richer typing a future {@code Signal.payload()}
     * producer might carry.
     */
    private String buildEventPayload(Signal signal) {
        Map<String, Object> payload = new HashMap<>(signal.flatAttributes());
        payload.put("type", "conductor.work_item.status_changed");
        return toJson(payload);
    }

    /**
     * Passes when no action filter is declared, or the signal's action matches any declared entry
     * (case-insensitive); and when no label filter is declared, or the signal carries a {@code label}
     * metadata key matching any declared entry. A non-{@code labeled} action has no {@code label} key
     * at all, so a declared {@code labelFilter} correctly excludes it unless the action filter also
     * separately matches.
     */
    private boolean passesPrFilters(GitHubPullRequestTrigger trigger, Signal signal) {
        List<String> actionFilter = trigger.actionFilter();
        if (!actionFilter.isEmpty()) {
            String action = signal.flatAttributes().get("action");
            if (actionFilter.stream().noneMatch(a -> a.equalsIgnoreCase(action))) return false;
        }
        List<String> labelFilter = trigger.labelFilter();
        if (!labelFilter.isEmpty()) {
            String label = signal.flatAttributes().get("label");
            if (label == null || labelFilter.stream().noneMatch(l -> l.equalsIgnoreCase(label))) return false;
        }
        return true;
    }

    /** See {@link #buildEventPayload(Signal)} for why this reads {@code flatAttributes()} not {@code payload()}. */
    private String buildPullRequestEventPayload(Signal signal) {
        Map<String, Object> payload = new HashMap<>(signal.flatAttributes());
        payload.put("type", "github.pull_request");
        return toJson(payload);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
