package com.conductor.workflow.signal;

import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.workflow.WorkflowTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replaces the two {@code workflowTriggerService.onConductorEvent}/{@code onGitHubPullRequest} calls in
 * today's {@code NotificationDispatcher.dispatch} with a single {@link SignalSubscriber} at {@link
 * SignalDispatchOrder#WORKFLOW_AUTOMATION}. Translates the {@link Signal} back into a {@link
 * NotificationEvent} and calls both existing, UNMODIFIED methods on {@link WorkflowTriggerService} --
 * in the same order, each wrapped in its own try/catch with the same log messages as today, so a
 * failure in one does not prevent the other from running.
 */
@Component
public class WorkflowAutomationSignalSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAutomationSignalSubscriber.class);

    private final WorkflowTriggerService workflowTriggerService;
    private final NotificationSignalMapper mapper;

    public WorkflowAutomationSignalSubscriber(WorkflowTriggerService workflowTriggerService,
                                               NotificationSignalMapper mapper) {
        this.workflowTriggerService = workflowTriggerService;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "workflow-automation";
    }

    @Override
    public boolean interestedIn(String signalType) {
        // A6 will narrow this: today both onConductorEvent and onGitHubPullRequest are called
        // unconditionally for every event type, each doing its own internal EventType check.
        return true;
    }

    @Override
    public void onSignal(Signal signal) {
        NotificationEvent event = mapper.toNotificationEvent(signal);

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
    }

    @Override
    public int order() {
        return SignalDispatchOrder.WORKFLOW_AUTOMATION;
    }
}
