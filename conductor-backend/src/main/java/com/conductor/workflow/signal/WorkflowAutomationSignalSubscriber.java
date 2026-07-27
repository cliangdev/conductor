package com.conductor.workflow.signal;

import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.WorkflowTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A {@link SignalSubscriber} at {@link SignalDispatchOrder#WORKFLOW_AUTOMATION} that calls both {@link
 * WorkflowTriggerService#onConductorEvent(Signal)} and {@link
 * WorkflowTriggerService#onGitHubPullRequest(Signal)} -- in that order, each wrapped in its own
 * try/catch, so a failure in one does not prevent the other from running. Interested in exactly the two
 * signal types either method acts on; each method also re-checks its own type as defense-in-depth (see
 * their javadoc).
 */
@Component
public class WorkflowAutomationSignalSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAutomationSignalSubscriber.class);

    private final WorkflowTriggerService workflowTriggerService;

    public WorkflowAutomationSignalSubscriber(WorkflowTriggerService workflowTriggerService) {
        this.workflowTriggerService = workflowTriggerService;
    }

    @Override
    public String name() {
        return "workflow-automation";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signalType)
                || SignalTypes.GITHUB_PULL_REQUEST.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        try {
            workflowTriggerService.onConductorEvent(signal);
        } catch (Exception e) {
            log.warn("Workflow trigger evaluation failed for signal {}: {}", signal.type(), e.getMessage());
        }

        try {
            workflowTriggerService.onGitHubPullRequest(signal);
        } catch (Exception e) {
            log.warn("GitHub PR workflow trigger evaluation failed for signal {}: {}", signal.type(), e.getMessage());
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.WORKFLOW_AUTOMATION;
    }
}
