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

    public WorkflowTriggerService(WorkflowDefinitionRepository workflowRepository,
                                   WorkflowRunRepository workflowRunRepository,
                                   @Lazy WorkflowExecutionEngine executionEngine,
                                   WorkflowScheduleRepository scheduleRepository,
                                   ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.executionEngine = executionEngine;
        this.scheduleRepository = scheduleRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Called by NotificationDispatcher after a conductor event fires.
     * Finds all enabled workflows in the project with matching trigger and creates WorkflowRun rows.
     */
    @Transactional
    public void onConductorEvent(NotificationEvent event) {
        if (event.getEventType() != EventType.ISSUE_STATUS_CHANGED) return;

        String projectId = event.getProjectId();
        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);

        for (WorkflowDefinition workflow : workflows) {
            if (!workflow.isEnabled()) continue;
            if (!hasConductorIssueTrigger(workflow.getYaml())) continue;
            if (!passesStatusFilter(workflow.getYaml(), event)) continue;

            createRun(workflow, "conductor.issue.status_changed", buildEventPayload(event));
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
        try {
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = snakeYaml.load(yaml);
            Object onBlock = parsed.containsKey("on") ? parsed.get("on") : parsed.get(Boolean.TRUE);
            if (!(onBlock instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> triggers = (Map<String, Object>) onBlock;
            Object scheduleTrigger = triggers.get("schedule");
            if (!(scheduleTrigger instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleConfig = (Map<String, Object>) scheduleTrigger;
            Object cronVal = scheduleConfig.get("cron");
            return cronVal != null ? cronVal.toString().trim() : null;
        } catch (Exception e) {
            log.warn("Failed to parse schedule trigger from YAML: {}", e.getMessage());
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
        enqueueInitialJobs(workflow, saved);
        return saved;
    }

    private void enqueueInitialJobs(WorkflowDefinition workflow, WorkflowRun run) {
        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> parsed = yaml.load(workflow.getYaml());
            Object jobsObj = parsed.get("jobs");
            if (!(jobsObj instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> jobs = (Map<String, Object>) jobsObj;

            // Collect condition step targets — these jobs should NOT be enqueued upfront;
            // they are enqueued at runtime when the condition step evaluates.
            java.util.Set<String> conditionTargets = collectConditionTargets(jobs);

            for (Map.Entry<String, Object> entry : jobs.entrySet()) {
                String jobId = entry.getKey();
                if (!(entry.getValue() instanceof Map)) continue;
                if (conditionTargets.contains(jobId)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> job = (Map<String, Object>) entry.getValue();
                Object needs = job.get("needs");
                if (needs == null || (needs instanceof List && ((List<?>) needs).isEmpty())) {
                    executionEngine.enqueueJob(run.getId(), jobId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enqueue initial jobs for run {}: {}", run.getId(), e.getMessage());
        }
    }

    /**
     * Returns the set of job IDs that are targets of a condition step (then/else).
     * These jobs must not be auto-enqueued at workflow start — they are triggered
     * only when the condition step evaluates and routes to them.
     */
    @SuppressWarnings("unchecked")
    private java.util.Set<String> collectConditionTargets(Map<String, Object> jobs) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (Map.Entry<String, Object> entry : jobs.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<String, Object> job = (Map<String, Object>) entry.getValue();
            Object stepsObj = job.get("steps");
            if (!(stepsObj instanceof java.util.List)) continue;
            for (Object stepObj : (java.util.List<?>) stepsObj) {
                if (!(stepObj instanceof Map)) continue;
                Map<String, Object> step = (Map<String, Object>) stepObj;
                if (!"condition".equals(step.get("type"))) continue;
                Object then = step.get("then");
                Object else_ = step.get("else");
                if (then instanceof String) targets.add((String) then);
                if (else_ instanceof String) targets.add((String) else_);
            }
        }
        return targets;
    }

    private boolean hasConductorIssueTrigger(String yaml) {
        return yaml != null && yaml.contains("conductor.issue.status_changed");
    }

    private boolean passesStatusFilter(String yaml, NotificationEvent event) {
        try {
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parsed = snakeYaml.load(yaml);
            // SnakeYAML 1.1 parses bare 'on' as Boolean.TRUE
            Object onBlock = parsed.containsKey("on") ? parsed.get("on") : parsed.get(Boolean.TRUE);
            if (!(onBlock instanceof java.util.Map)) return true;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> triggers = (java.util.Map<String, Object>) onBlock;
            Object triggerConfig = triggers.get("conductor.issue.status_changed");
            if (!(triggerConfig instanceof java.util.Map)) return true;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> config = (java.util.Map<String, Object>) triggerConfig;
            Object filtersObj = config.get("filters");
            if (!(filtersObj instanceof java.util.Map)) return true;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> filters = (java.util.Map<String, Object>) filtersObj;
            Object statusFilter = filters.get("status");
            if (statusFilter == null) return true;
            String toStatus = event.getMetadata().get("toStatus");
            return statusFilter.toString().equalsIgnoreCase(toStatus);
        } catch (Exception e) {
            log.warn("Failed to parse trigger filters: {}", e.getMessage());
            return true;
        }
    }

    private String buildEventPayload(NotificationEvent event) {
        Map<String, Object> payload = new HashMap<>(event.getMetadata());
        payload.put("type", "conductor.issue.status_changed");
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
