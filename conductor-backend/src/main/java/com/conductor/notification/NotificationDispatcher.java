package com.conductor.notification;

import com.conductor.knowledge.KnowledgeEventTap;
import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.workflow.WorkflowTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * In-process event fan-out. Despite the name this is the event bus, not a notifier -- chat delivery is
 * one of five things it does, and now lives in {@link NotificationDeliveryService}.
 *
 * <p>Note the asymmetry in {@link #dispatch}: delivery runs first and is NOT wrapped in a try/catch,
 * while the four downstream consumers each are. A failing notification-config lookup therefore escapes
 * and short-circuits the rest. See {@link NotificationDeliveryService} for why that is load-bearing.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationDeliveryService deliveryService;

    @Lazy
    @Autowired
    private WorkflowTriggerService workflowTriggerService;

    @Lazy
    @Autowired
    private LifecycleTriggerDispatcher lifecycleTriggerDispatcher;

    @Lazy
    @Autowired
    private KnowledgeEventTap knowledgeEventTap;

    public NotificationDispatcher(NotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public void dispatch(NotificationEvent event) {
        deliveryService.deliver(event);

        try {
            workflowTriggerService.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Workflow trigger evaluation failed for event {}: {}", event.getEventType(), e.getMessage());
        }

        try {
            workflowTriggerService.onGitHubPullRequest(event);
        } catch (Exception e) {
            log.warn("GitHub PR workflow trigger evaluation failed for event {}: {}", event.getEventType(), e.getMessage());
        }

        try {
            lifecycleTriggerDispatcher.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Lifecycle trigger evaluation failed for event {}: {}", event.getEventType(), e.getMessage());
        }

        try {
            knowledgeEventTap.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Knowledge ingestion tap failed for event {}: {}", event.getEventType(), e.getMessage());
        }
    }
}
