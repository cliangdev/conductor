package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ObjectMapper objectMapper;

    public WorkflowTriggerService(WorkflowDefinitionRepository workflowRepository,
                                   WorkflowRunRepository workflowRunRepository,
                                   ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
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

    private WorkflowRun createRun(WorkflowDefinition workflow, String triggerType, String eventPayloadJson) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setTriggerType(triggerType);
        run.setEventPayload(eventPayloadJson);
        run.setStatus(WorkflowRunStatus.PENDING);
        WorkflowRun saved = workflowRunRepository.save(run);
        log.info("Created WorkflowRun {} for workflow {} (trigger: {})", saved.getId(), workflow.getId(), triggerType);
        return saved;
    }

    private boolean hasConductorIssueTrigger(String yaml) {
        return yaml != null && yaml.contains("conductor.issue.status_changed");
    }

    private boolean passesStatusFilter(String yaml, NotificationEvent event) {
        try {
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> parsed = snakeYaml.load(yaml);
            Object onBlock = parsed.get("on");
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
