package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.workflow.model.ConductorEventTrigger;
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

    public WorkflowTriggerService(WorkflowDefinitionRepository workflowRepository,
                                   WorkflowRunRepository workflowRunRepository,
                                   @Lazy WorkflowExecutionEngine executionEngine,
                                   WorkflowScheduleRepository scheduleRepository,
                                   ObjectMapper objectMapper,
                                   WorkflowYamlParser yamlParser) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.executionEngine = executionEngine;
        this.scheduleRepository = scheduleRepository;
        this.objectMapper = objectMapper;
        this.yamlParser = yamlParser;
    }

    /**
     * Called by NotificationDispatcher after a conductor event fires.
     * Finds all enabled workflows in the project with matching trigger and creates WorkflowRun rows.
     */
    @Transactional
    public void onConductorEvent(NotificationEvent event) {
        if (event.getEventType() != EventType.WORK_ITEM_STATUS_CHANGED) return;

        String projectId = event.getProjectId();
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
            if (!passesStatusFilter(trigger, event)) continue;

            createRun(workflow, "conductor.work_item.status_changed", buildEventPayload(event));
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
     * Creates a run for manual dispatch. Called from WorkflowDispatchController.
     */
    @Transactional
    public WorkflowRun triggerManual(WorkflowDefinition workflow, String triggeredByUserId) {
        Map<String, Object> payload = Map.of(
                "type", "workflow_dispatch",
                "triggeredBy", triggeredByUserId,
                "triggeredAt", java.time.OffsetDateTime.now().toString()
        );
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
        enqueueInitialJobs(workflow, saved);

        return saved;
    }

    private void enqueueInitialJobs(WorkflowDefinition workflow, WorkflowRun run) {
        WorkflowSpec spec = parseYaml(workflow.getYaml());
        if (spec == null) {
            log.warn("Failed to enqueue initial jobs for run {}: could not parse workflow YAML", run.getId());
            return;
        }
        Map<String, JobSpec> jobs = spec.jobs();

        // Collect condition step targets — these jobs should NOT be enqueued upfront;
        // they are enqueued at runtime when the condition step evaluates.
        java.util.Set<String> conditionTargets = collectConditionTargets(jobs);

        for (JobSpec job : jobs.values()) {
            if (conditionTargets.contains(job.id())) continue;
            if (job.needs().isEmpty()) {
                executionEngine.enqueueJob(run.getId(), job.id());
            }
        }
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

    /** Passes when no status filter is declared, or the event's target status matches any declared entry. */
    private boolean passesStatusFilter(ConductorEventTrigger trigger, NotificationEvent event) {
        List<String> statusFilter = trigger.statusFilter();
        if (statusFilter.isEmpty()) return true;
        String toStatus = event.getMetadata().get("toStatus");
        return statusFilter.stream().anyMatch(s -> s.equalsIgnoreCase(toStatus));
    }

    private String buildEventPayload(NotificationEvent event) {
        Map<String, Object> payload = new HashMap<>(event.getMetadata());
        payload.put("type", "conductor.work_item.status_changed");
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
