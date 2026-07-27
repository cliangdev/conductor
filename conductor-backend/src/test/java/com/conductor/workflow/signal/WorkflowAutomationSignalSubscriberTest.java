package com.conductor.workflow.signal;

import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.WorkflowTriggerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkflowAutomationSignalSubscriberTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private WorkflowTriggerService workflowTriggerService;

    private Signal signal(String type) {
        return Signal.of(type, PROJECT_ID, null, Instant.now(), Map.of("workItemId", "wi-1"),
                new SignalOrigin("test", null));
    }

    private Signal statusChangedSignal() {
        return signal(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED);
    }

    @Test
    void orderIsWorkflowAutomation() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        assertThat(sub.order()).isEqualTo(SignalDispatchOrder.WORKFLOW_AUTOMATION);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        assertThat(sub.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    /**
     * Narrowed in A6: {@code interestedIn} now filters to exactly the two types either downstream method
     * acts on, using exact string equality -- {@code github.pull_request} and {@code
     * github.pull_request_merged} must not both match (see {@link SignalTypes}'s javadoc on why the merged
     * type is a deliberately unrelated flat string).
     */
    @Test
    void interestedInConductorStatusChangedAndGitHubPullRequestOnly() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        assertThat(sub.interestedIn(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED)).isTrue();
        assertThat(sub.interestedIn(SignalTypes.GITHUB_PULL_REQUEST)).isTrue();
        assertThat(sub.interestedIn(SignalTypes.GITHUB_PULL_REQUEST_MERGED)).isFalse();
        assertThat(sub.interestedIn("anything")).isFalse();
    }

    @Test
    void onSignalCallsConductorEventThenGitHubPullRequestInOrder() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        Signal signal = statusChangedSignal();

        sub.onSignal(signal);

        InOrder order = inOrder(workflowTriggerService);
        order.verify(workflowTriggerService).onConductorEvent(signal);
        order.verify(workflowTriggerService).onGitHubPullRequest(signal);
    }

    @Test
    void conductorEventFailureIsIsolatedFromGitHubPullRequestCall() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        doThrow(new RuntimeException("boom")).when(workflowTriggerService).onConductorEvent(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(statusChangedSignal()));

        verify(workflowTriggerService).onGitHubPullRequest(any());
    }

    @Test
    void gitHubPullRequestFailureDoesNotEscapeOnSignal() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService);
        doThrow(new RuntimeException("boom")).when(workflowTriggerService).onGitHubPullRequest(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(statusChangedSignal()));

        verify(workflowTriggerService).onConductorEvent(any());
    }
}
