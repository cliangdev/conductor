package com.conductor.workflow.signal;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.workflow.WorkflowTriggerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private final NotificationSignalMapper mapper = new NotificationSignalMapper();

    private Signal signal() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "wi-1"));
        return mapper.toSignal(event);
    }

    @Test
    void orderIsWorkflowAutomation() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);
        assertThat(sub.order()).isEqualTo(SignalDispatchOrder.WORKFLOW_AUTOMATION);
    }

    @Test
    void failureModeDefaultsToSwallow() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);
        assertThat(sub.failureMode()).isEqualTo(FailureMode.SWALLOW);
    }

    @Test
    void interestedInEveryType() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);
        assertThat(sub.interestedIn("anything")).isTrue();
    }

    @Test
    void onSignalCallsConductorEventThenGitHubPullRequestInOrder() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);

        sub.onSignal(signal());

        InOrder order = inOrder(workflowTriggerService);
        order.verify(workflowTriggerService).onConductorEvent(any());
        order.verify(workflowTriggerService).onGitHubPullRequest(any());
    }

    @Test
    void conductorEventFailureIsIsolatedFromGitHubPullRequestCall() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);
        doThrow(new RuntimeException("boom")).when(workflowTriggerService).onConductorEvent(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(signal()));

        verify(workflowTriggerService).onGitHubPullRequest(any());
    }

    @Test
    void gitHubPullRequestFailureDoesNotEscapeOnSignal() {
        WorkflowAutomationSignalSubscriber sub = new WorkflowAutomationSignalSubscriber(workflowTriggerService, mapper);
        doThrow(new RuntimeException("boom")).when(workflowTriggerService).onGitHubPullRequest(any());

        assertThatNoException().isThrownBy(() -> sub.onSignal(signal()));

        verify(workflowTriggerService).onConductorEvent(any());
    }
}
